package com.mieai.qqbot.module.host

import com.mieai.qqbot.module.api.FrameworkVersion
import com.mieai.qqbot.module.api.ModuleDependency
import com.mieai.qqbot.module.api.ModuleDescriptor

internal object ModuleGraph {
    fun resolve(descriptors: Map<String, ModuleDescriptor>): List<String> {
        val frameworkVersion = SemanticVersion.parse(FrameworkVersion.CURRENT)
        descriptors.values.forEach { descriptor ->
            descriptor.webContributions.forEach { contribution ->
                check(contribution.componentKey == null) {
                    "External module ${descriptor.id} cannot contribute a built-in Web component"
                }
                val expected = "/modules/${descriptor.id}/${contribution.id}"
                check(expected == contribution.route) {
                    "Web contribution ${descriptor.id}:${contribution.id} must use route $expected"
                }
            }
            check(frameworkVersion >= SemanticVersion.parse(descriptor.minimumFrameworkVersion)) {
                "Module ${descriptor.id} requires framework ${descriptor.minimumFrameworkVersion}"
            }
            descriptor.dependencies.forEach dependencyLoop@{ dependency ->
                val target = descriptors[dependency.moduleId]
                if (target == null) {
                    check(dependency.optional) {
                        "Module ${descriptor.id} requires missing module ${dependency.moduleId}"
                    }
                    return@dependencyLoop
                }
                check(SemanticVersion.parse(target.version) >= SemanticVersion.parse(dependency.minimumVersion)) {
                    "Module ${descriptor.id} requires ${dependency.moduleId} ${dependency.minimumVersion} or newer"
                }
            }
        }
        val order = mutableListOf<String>()
        val visits = mutableMapOf<String, VisitState>()
        descriptors.keys.sorted().forEach { visit(it, descriptors, visits, order, mutableListOf()) }
        return order.toList()
    }

    private fun visit(id: String, descriptors: Map<String, ModuleDescriptor>, visits: MutableMap<String, VisitState>, order: MutableList<String>, path: MutableList<String>) {
        when (visits[id]) {
            VisitState.COMPLETE -> return
            VisitState.VISITING -> {
                path.add(id)
                throw IllegalStateException("Framework module dependency cycle: ${path.joinToString(" -> ")}")
            }
            null -> Unit
        }
        visits[id] = VisitState.VISITING
        path.add(id)
        descriptors.getValue(id).dependencies
            .filter { descriptors.containsKey(it.moduleId) }
            .sortedBy { it.moduleId }
            .forEach { visit(it.moduleId, descriptors, visits, order, path.toMutableList()) }
        visits[id] = VisitState.COMPLETE
        order.add(id)
    }

    private enum class VisitState { VISITING, COMPLETE }
}
