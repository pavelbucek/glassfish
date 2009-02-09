/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.deployment.admin;

import com.sun.enterprise.config.serverbeans.Application;
import com.sun.enterprise.config.serverbeans.Applications;
import com.sun.enterprise.deploy.shared.ArchiveFactory;
import com.sun.enterprise.util.LocalStringManagerImpl;
import com.sun.enterprise.util.io.FileUtils;
import com.sun.enterprise.v3.admin.CommandRunner;
import java.net.URI;
import org.glassfish.internal.data.ApplicationInfo;
import org.glassfish.internal.deployment.Deployment;
import org.glassfish.internal.deployment.ExtendedDeploymentContext;
import com.sun.enterprise.v3.server.SnifferManager;
import com.sun.enterprise.config.serverbeans.ConfigBeansUtilities;
import org.glassfish.server.ServerEnvironmentImpl;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.ServerTags;
import org.glassfish.api.ActionReport;
import org.glassfish.api.I18n;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.deployment.archive.ArchiveHandler;
import org.glassfish.api.deployment.archive.ReadableArchive;
import org.glassfish.api.deployment.DeployCommandParameters;
import org.glassfish.api.deployment.UndeployCommandParameters;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.PerLookup;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.glassfish.deployment.common.DeploymentProperties;


/**
 * Deploy command
 *
 * @author Jerome Dochez
 */
@Service(name="deploy")
@I18n("deploy.command")
@Scoped(PerLookup.class)
public class DeployCommand extends DeployCommandParameters implements AdminCommand {

    final private static LocalStringManagerImpl localStrings = new LocalStringManagerImpl(DeployCommand.class);

    private static final String INSTANCE_ROOT_URI_PROPERTY_NAME = "com.sun.aas.instanceRootURI";

    @Inject
    Applications apps;

    @Inject
    ServerEnvironmentImpl env;

    @Inject
    CommandRunner commandRunner;

    @Inject
    Deployment deployment;

    @Inject
    SnifferManager snifferManager;

    @Inject
    ArchiveFactory archiveFactory;

    @Inject
    Domain domain;

    public DeployCommand() {
        origin = Origin.deploy;
    }

    /**
     * Entry point from the framework into the command execution
     * @param context context for the command.
     */
    public void execute(AdminCommandContext context) {

        long operationStartTime = Calendar.getInstance().getTimeInMillis();

        //final Properties parameters = context.getCommandParameters();
        final ActionReport report = context.getActionReport();
        final Logger logger = context.getLogger();

        File file = choosePathFile(context);
        if (!file.exists()) {
            report.setMessage(localStrings.getLocalString("fnf","File not found", file.getAbsolutePath()));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }

        deploymentplan = chooseDeploymentPlanFile(context);
        
        if (snifferManager.hasNoSniffers()) {
            String msg = localStrings.getLocalString("nocontainer", "No container services registered, done...");
            report.failure(logger,msg);
            return;
        }

        ReadableArchive archive;
        try {
            archive = archiveFactory.openArchive(file);
        } catch (IOException e) {
            if (logReportedErrors) {
                report.failure(logger,"Error opening deployable artifact : " + file.getAbsolutePath(),e);
            } else {
                report.setMessage("Error opening deployable artifact : " + file.getAbsolutePath() + e.toString());
                report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            }
            return;
        }
        File expansionDir=null;
        try {

            ArchiveHandler archiveHandler = deployment.getArchiveHandler(archive);
            if (archiveHandler==null) {
                report.failure(logger,localStrings.getLocalString("deploy.unknownarchivetype","Archive type of {0} was not recognized",file.getName()));
                return;
            }
            // get an application name
            if (name==null) {
                // Archive handlers know how to construct default app names.
                name = archiveHandler.getDefaultApplicationName(archive);
            }

            Properties undeployProps = handleRedeploy(name, report);

            // clean up any left over repository files
            if ( ! keepreposdir.booleanValue()) {
                FileUtils.whack(new File(env.getApplicationRepositoryPath(), name));
            }

            File source = new File(archive.getURI().getSchemeSpecificPart());
            boolean isDirectoryDeployed = true;
            if (!source.isDirectory()) {
                isDirectoryDeployed = false;
                expansionDir = new File(domain.getApplicationRoot(), name);
                if (!expansionDir.mkdirs()) {
                    /*
                     * On Windows especially a previous directory might have
                     * remainded after an earlier undeployment, for example if
                     * a JAR file in the earlier deployment had been locked.
                     * Warn but do not fail in such a case.
                     */
                    logger.fine(localStrings.getLocalString("deploy.cannotcreateexpansiondir", "Error while creating directory for jar expansion: {0}",expansionDir));
                }
                try {
                    Long start = System.currentTimeMillis();
                    archiveHandler.expand(archive, archiveFactory.createArchive(expansionDir));
                    System.out.println("Deployment expansion took " + (System.currentTimeMillis() - start));
                    
                    // Close the JAR archive before losing the reference to it or else the JAR remains locked.
                    try {
                        archive.close();
                    } catch(IOException e) {
                        report.failure(logger,localStrings.getLocalString("deploy.errorclosingarchive","Error while closing deployable artifact {0}", file.getAbsolutePath()),e);
                        return;
                    }                                                        
                    // Proceed using the expanded directory.
                    file = expansionDir;
                    archive = archiveFactory.openArchive(expansionDir);
                } catch(IOException e) {
                    report.failure(logger,localStrings.getLocalString("deploy.errorexpandingjar","Error while expanding archive file"),e);
                    return;

                }
            }

            // create the parent class loader
            final ReadableArchive sourceArchive = archive;
            final ExtendedDeploymentContext deploymentContext = deployment.getContext(logger, sourceArchive, this);

            // reset the properties (might be null) set by the deployers when undeploying.
            deploymentContext.getProps().putAll(undeployProps);

            if (properties!=null) {
                deploymentContext.getProps().putAll(properties);
            }

            // clean up any generated files
            deploymentContext.clean();

            Properties moduleProps = deploymentContext.getProps();
            moduleProps.setProperty(ServerTags.NAME, name);
            /*
             * If the app's location is within the domain's directory then
             * express it in the config as ${com.sun.aas.instanceRootURI}/rest-of-path
             * so users can relocate the entire installation without having
             * to modify the app locations.  Leave the location alone if
             * it does not fall within the domain directory.
             */
            URI instanceRootURI = new URI(System.getProperty(INSTANCE_ROOT_URI_PROPERTY_NAME));
            URI appURI = instanceRootURI.relativize(deploymentContext.getSource().getURI());
            String appLocation = (appURI.isAbsolute()) ?
                appURI.toString() :
                "${" + INSTANCE_ROOT_URI_PROPERTY_NAME + "}/" + appURI.toString();
            moduleProps.setProperty(ServerTags.LOCATION, appLocation);
            // set to default "user", deployers can override it
            // during processing
            moduleProps.setProperty(ServerTags.OBJECT_TYPE, "user");
            if (contextRoot!=null) {
                moduleProps.setProperty(ServerTags.CONTEXT_ROOT, contextRoot);
            }
            if (libraries!=null) {
                moduleProps.setProperty(ServerTags.LIBRARIES, libraries);
            }
            moduleProps.setProperty(ServerTags.ENABLED, enabled.toString());
            moduleProps.setProperty(ServerTags.DIRECTORY_DEPLOYED, String.valueOf(isDirectoryDeployed));
            if (virtualservers != null) {
                moduleProps.setProperty(ServerTags.VIRTUAL_SERVERS,
                    virtualservers);
            }
            if (description != null) {
                moduleProps.setProperty(ServerTags.DESCRIPTION, description);
            }

            ApplicationInfo appInfo = deployment.deploy(deploymentContext, report);
            if (report.getActionExitCode()==ActionReport.ExitCode.SUCCESS) {
                // register application information in domain.xml
                deployment.registerAppInDomainXML(appInfo, deploymentContext);

            }
        } catch(Exception e) {
            report.failure(logger,"Error during deployment : "+e.getMessage(),e);
        } finally {
            try {
                archive.close();
            } catch(IOException e) {
                logger.log(Level.INFO, "Error while closing deployable artifact : " + file.getAbsolutePath(), e);
            }
            if (report.getActionExitCode().equals(ActionReport.ExitCode.SUCCESS)) {
                logger.info("Deployment of " + name + " done is "
                        + (Calendar.getInstance().getTimeInMillis() - operationStartTime) + " ms");
            } else {
                if (expansionDir!=null) {
                   FileUtils.whack(expansionDir);
                }
            }
        }
    }

    private File choosePathFile(AdminCommandContext context) {
        if (context.getUploadedFiles().size() >= 1) {
            /*
             * Use the uploaded file rather than the one specified by --path.
             */
            return context.getUploadedFiles().get(0);
        }
        return path;
    }
    
    private File chooseDeploymentPlanFile(AdminCommandContext context) {
        if (context.getUploadedFiles().size() >= 2) {
            /*
             * Use the uploaded file rather than the one specified by
             * --deploymentplan.
             */
            return context.getUploadedFiles().get(1);
        }
        return deploymentplan;
    }
    
    /**
     *  Check if the application is deployed or not.
     *  If force option is true and appInfo is not null, then undeploy
     *  the application and return false.  This will force deployment
     *  if there's already a running application deployed.
     *
     *  @param name application name
     *  @param report ActionReport, report object to send back to client.
     * @return context properties that might have been set by the deployers
     * while undeploying the application
     *
     */
    private Properties handleRedeploy(final String name, final ActionReport report)
        throws Exception {
        boolean isRegistered = deployment.isRegistered(name);
        if (isRegistered && !force) {
            String msg = localStrings.getLocalString(
                "application.alreadyreg.redeploy",
                "Application {0} already registered, please use deploy --force=true to redeploy", name);
            throw new Exception(msg);
        }
        else if (isRegistered && force) 
        {
            //preserve settings first before undeploy
            Application app = apps.getModule(Application.class, name);

            // we save some of the old registration information in our deployment parameters
            settingsFromDomainXML(app);

            //if applicaiton is already deployed and force=true,
            //then undeploy the application first.
            UndeployCommandParameters undeployParams = new UndeployCommandParameters(name);
            undeployParams.keepreposdir = keepreposdir;

            ActionReport subReport = report.addSubActionsReport();
            if (properties!=null && properties.containsKey(DeploymentProperties.KEEP_SESSIONS)) {
                undeployParams.properties = new Properties();
                undeployParams.properties.put(DeploymentProperties.KEEP_SESSIONS, properties.getProperty(DeploymentProperties.KEEP_SESSIONS));
                subReport.setExtraProperties(new Properties());
            }
            commandRunner.doCommand("undeploy", undeployParams, subReport, null);
            return subReport.getExtraProperties();
        }
        return null;
    }

    
    /**
     *  Get settings from domain.xml and preserve the values.
     *  This is a private api and its invoked when --force=true and if the app is registered.
     *
     *  @param app is the registration information about the previously deployed application
     *
     */
    private void settingsFromDomainXML(Application app) {
            //if name is null then cannot get the application's setting from domain.xml
        if (name != null) {
            if (contextRoot == null) {            
                contextRoot = app.getContextRoot();
                if (contextRoot != null) {
                    this.previousContextRoot = contextRoot;
                }
            }
            if (libraries == null) {
                libraries = app.getLibraries();
            }
            if (virtualservers == null) {
                virtualservers = ConfigBeansUtilities.getVirtualServers(
                    target, name);
            }

        }
    }
}
