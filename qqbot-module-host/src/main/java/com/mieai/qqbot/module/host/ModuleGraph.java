package com.mieai.qqbot.module.host;

import com.mieai.qqbot.module.api.FrameworkVersion;
import com.mieai.qqbot.module.api.ModuleDependency;
import com.mieai.qqbot.module.api.ModuleDescriptor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ModuleGraph {
    private ModuleGraph() {}

    static List<String> resolve(Map<String, ModuleDescriptor> descriptors) {
        SemanticVersion frameworkVersion = SemanticVersion.parse(FrameworkVersion.CURRENT);
        for (ModuleDescriptor descriptor : descriptors.values()) {
            descriptor.webContributions().forEach(contribution -> {
                if (contribution.componentKey() != null) {
                    throw new IllegalStateException("External module " + descriptor.id()
                            + " cannot contribute a built-in Web component");
                }
                String expected = "/modules/" + descriptor.id() + "/" + contribution.id();
                if (!expected.equals(contribution.route())) {
                    throw new IllegalStateException("Web contribution " + descriptor.id() + ":"
                            + contribution.id() + " must use route " + expected);
                }
            });
            if (frameworkVersion.compareTo(
                    SemanticVersion.parse(descriptor.minimumFrameworkVersion())) < 0) {
                throw new IllegalStateException("Module " + descriptor.id()
                        + " requires framework " + descriptor.minimumFrameworkVersion());
            }
            for (ModuleDependency dependency : descriptor.dependencies()) {
                ModuleDescriptor target = descriptors.get(dependency.moduleId());
                if (target == null) {
                    if (!dependency.optional()) {
                        throw new IllegalStateException("Module " + descriptor.id()
                                + " requires missing module " + dependency.moduleId());
                    }
                    continue;
                }
                if (SemanticVersion.parse(target.version()).compareTo(
                        SemanticVersion.parse(dependency.minimumVersion())) < 0) {
                    throw new IllegalStateException("Module " + descriptor.id() + " requires "
                            + dependency.moduleId() + " " + dependency.minimumVersion()
                            + " or newer");
                }
            }
        }

        List<String> order = new ArrayList<>();
        Map<String, VisitState> visits = new HashMap<>();
        descriptors.keySet().stream().sorted()
                .forEach(id -> visit(id, descriptors, visits, order, new ArrayList<>()));
        return List.copyOf(order);
    }

    private static void visit(
            String id,
            Map<String, ModuleDescriptor> descriptors,
            Map<String, VisitState> visits,
            List<String> order,
            List<String> path) {
        VisitState state = visits.get(id);
        if (state == VisitState.COMPLETE) return;
        if (state == VisitState.VISITING) {
            path.add(id);
            throw new IllegalStateException("Framework module dependency cycle: "
                    + String.join(" -> ", path));
        }
        visits.put(id, VisitState.VISITING);
        path.add(id);
        descriptors.get(id).dependencies().stream()
                .filter(dependency -> descriptors.containsKey(dependency.moduleId()))
                .sorted(java.util.Comparator.comparing(ModuleDependency::moduleId))
                .forEach(dependency -> visit(dependency.moduleId(), descriptors, visits, order,
                        new ArrayList<>(path)));
        visits.put(id, VisitState.COMPLETE);
        order.add(id);
    }

    private enum VisitState { VISITING, COMPLETE }
}
