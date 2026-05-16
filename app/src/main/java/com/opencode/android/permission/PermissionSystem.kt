package com.opencode.android.permission

data class PermissionRule(
    val permission: String,
    val pattern: String,
    val action: PermissionAction
)

enum class PermissionAction { Allow, Deny, Ask }

data class PermissionResult(
    val action: PermissionAction,
    val rule: PermissionRule? = null
)

class PermissionSystem {
    private val rules = mutableListOf<PermissionRule>()

    init {
        rules.addAll(defaultRules())
    }

    fun addRule(rule: PermissionRule) {
        rules.add(rule)
    }

    fun setRules(newRules: List<PermissionRule>) {
        rules.clear()
        rules.addAll(newRules)
    }

    fun getRules(): List<PermissionRule> = rules.toList()

    fun evaluate(permission: String, pattern: String): PermissionResult {
        val matched = rules.findLast { rule ->
            wildcardMatch(rule.permission, permission) && wildcardMatch(rule.pattern, pattern)
        }

        return when {
            matched != null -> PermissionResult(matched.action, matched)
            else -> PermissionResult(PermissionAction.Allow)
        }
    }

    private fun wildcardMatch(pattern: String, input: String): Boolean {
        val regex = StringBuilder("^")
        var i = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '*' -> regex.append(".*")
                '?' -> regex.append(".")
                else -> regex.append(Regex.escape(pattern[i].toString()))
            }
            i++
        }
        regex.append("$")
        return Regex(regex.toString()).matches(input)
    }

    private fun defaultRules(): List<PermissionRule> {
        return listOf(
            PermissionRule("*", "*", PermissionAction.Allow)
        )
    }

    companion object {
        @Volatile
        private var instance: PermissionSystem? = null

        fun getInstance(): PermissionSystem {
            return instance ?: synchronized(this) {
                instance ?: PermissionSystem().also { instance = it }
            }
        }
    }
}
