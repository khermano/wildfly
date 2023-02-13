package org.wildfly.extension.health;

import org.jboss.as.server.deployment.Attachments;
import org.jboss.as.server.deployment.module.ModuleDependency;
import org.jboss.as.server.deployment.DeploymentUnitProcessor;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.as.server.deployment.module.ModuleSpecification;
import org.jboss.logging.Logger;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleLoader;
import org.wildfly.extension.health._private.HealthLogger;

import java.util.Optional;

public class HelperDeploymentDependencyProcessor implements DeploymentUnitProcessor {

    private HealthLogger LOGGER = Logger.getMessageLogger(HealthLogger.class, "org.wildfly.extension.health");

    @Override
    public void deploy(DeploymentPhaseContext deploymentPhaseContext) throws DeploymentUnitProcessingException {
        DeploymentUnit deploymentUnit = deploymentPhaseContext.getDeploymentUnit();
        addModuleDependencies(deploymentUnit);
    }

    private void addModuleDependencies(DeploymentUnit deploymentUnit) {
        final ModuleSpecification moduleSpecification = deploymentUnit.getAttachment(Attachments.MODULE_SPECIFICATION);
        final ModuleLoader moduleLoader = Module.getBootModuleLoader();

        moduleSpecification.addSystemDependency(new ModuleDependency(moduleLoader, "org.eclipse.microprofile.lra.api", false, false, false, false, Optional.of("reason")));
        moduleSpecification.addLocalDependency(new ModuleDependency(moduleLoader, "org.jboss.jandex", false, false, false, false, Optional.of("reason")));
        moduleSpecification.addUserDependency(new ModuleDependency(moduleLoader, "org.jboss.as.weld.common", false, false, false, false, Optional.of("reason")));

        LOGGER.info("I AM IN! - HelperDeploymentDependencyProcessor is in action!!!!!!!!!!!!!!!!!!!");
    }
}
