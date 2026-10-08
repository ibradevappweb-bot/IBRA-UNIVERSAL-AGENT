package com.ibra.universalagent

enum class GoalStatus {
    PENDING,
    IN_PROGRESS,
    WAITING,
    COMPLETED,
    FAILED,
    BLOCKED,
    CANCELLED
}

enum class GoalStepStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    BLOCKED,
    SKIPPED
}

data class GoalStep(
    val id: String,
    val index: Int,
    val description: String,
    val action: PlanAction? = null,
    val dependsOn: List<String> = emptyList(),
    val status: GoalStepStatus = GoalStepStatus.PENDING,
    val result: String? = null
)

data class Goal(
    val id: String,
    val title: String,
    val request: String,
    val steps: List<GoalStep>,
    val status: GoalStatus = GoalStatus.PENDING,
    val currentStepId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class GoalProgress(
    val goalId: String,
    val status: GoalStatus,
    val completed: Int,
    val total: Int,
    val currentStep: GoalStep?,
    val readySteps: List<GoalStep>,
    val blockedSteps: List<GoalStep>
)

class TaskGoalEngine {
    private val goals = linkedMapOf<String, Goal>()

    fun createGoal(
        id: String,
        title: String,
        request: String,
        steps: List<GoalStep>,
        metadata: Map<String, String> = emptyMap()
    ): Goal {
        require(id.isNotBlank()) { "L'identifiant de la tâche est obligatoire." }
        require(title.isNotBlank()) { "Le titre de la tâche est obligatoire." }
        require(request.isNotBlank()) { "La demande est obligatoire." }
        require(steps.isNotEmpty()) { "Une tâche doit contenir au moins une étape." }
        require(steps.map { it.id }.distinct().size == steps.size) { "Les identifiants d'étape doivent être uniques." }

        val normalized = steps.sortedBy { it.index }
        validateDependencies(normalized)

        val goal = Goal(
            id = id.trim(),
            title = title.trim(),
            request = request.trim(),
            steps = normalized,
            metadata = metadata
        )
        goals[goal.id] = goal
        return goal
    }

    fun getGoal(id: String): Goal? = goals[id]

    fun listGoals(): List<Goal> = goals.values.toList()

    fun startGoal(id: String): Goal {
        val goal = requireGoal(id)
        if (goal.status == GoalStatus.COMPLETED || goal.status == GoalStatus.CANCELLED) return goal

        val ready = readySteps(goal)
        if (ready.isEmpty() && goal.steps.none { it.status == GoalStepStatus.COMPLETED }) {
            return updateGoalStatus(goal, GoalStatus.BLOCKED)
        }

        val next = ready.firstOrNull()
        val updatedSteps = if (next != null) {
            goal.steps.map {
                if (it.id == next.id) it.copy(status = GoalStepStatus.IN_PROGRESS)
                else it
            }
        } else {
            goal.steps
        }

        return updateGoal(
            goal.copy(
                status = if (next == null) GoalStatus.COMPLETED else GoalStatus.IN_PROGRESS,
                currentStepId = next?.id,
                steps = updatedSteps
            )
        )
    }

    fun completeCurrentStep(id: String, result: String? = null): Goal {
        val goal = requireGoal(id)
        val current = goal.currentStepId ?: return goal

        val updated = goal.steps.map {
            if (it.id == current) {
                it.copy(status = GoalStepStatus.COMPLETED, result = result)
            } else it
        }

        val intermediate = goal.copy(
            steps = updated,
            currentStepId = null,
            status = GoalStatus.IN_PROGRESS
        )
        return advance(intermediate)
    }

    fun failCurrentStep(id: String, reason: String? = null): Goal {
        val goal = requireGoal(id)
        val current = goal.currentStepId ?: return goal
        val updated = goal.steps.map {
            if (it.id == current) {
                it.copy(status = GoalStepStatus.FAILED, result = reason)
            } else it
        }
        return updateGoal(
            goal.copy(
                steps = updated,
                status = GoalStatus.FAILED,
                currentStepId = current
            )
        )
    }

    fun cancelGoal(id: String): Goal {
        val goal = requireGoal(id)
        return updateGoal(goal.copy(status = GoalStatus.CANCELLED))
    }

    fun progress(id: String): GoalProgress {
        val goal = requireGoal(id)
        val completed = goal.steps.count { it.status == GoalStepStatus.COMPLETED }
        val ready = readySteps(goal)
        val blocked = goal.steps.filter { step ->
            step.status == GoalStepStatus.PENDING && !dependenciesSatisfied(goal, step)
        }

        return GoalProgress(
            goalId = goal.id,
            status = goal.status,
            completed = completed,
            total = goal.steps.size,
            currentStep = goal.currentStepId?.let { currentId -> goal.steps.firstOrNull { it.id == currentId } },
            readySteps = ready,
            blockedSteps = blocked
        )
    }

    fun nextReadyStep(id: String): GoalStep? =
        readySteps(requireGoal(id)).firstOrNull()

    private fun advance(goal: Goal): Goal {
        if (goal.steps.all { it.status == GoalStepStatus.COMPLETED || it.status == GoalStepStatus.SKIPPED }) {
            return updateGoal(goal.copy(status = GoalStatus.COMPLETED, currentStepId = null))
        }

        val next = readySteps(goal).firstOrNull()
        if (next == null) {
            return updateGoal(goal.copy(status = GoalStatus.BLOCKED, currentStepId = null))
        }

        val updated = goal.steps.map {
            if (it.id == next.id) it.copy(status = GoalStepStatus.IN_PROGRESS)
            else it
        }

        return updateGoal(
            goal.copy(
                status = GoalStatus.IN_PROGRESS,
                currentStepId = next.id,
                steps = updated
            )
        )
    }

    private fun readySteps(goal: Goal): List<GoalStep> =
        goal.steps
            .filter { it.status == GoalStepStatus.PENDING }
            .filter { dependenciesSatisfied(goal, it) }
            .sortedBy { it.index }

    private fun dependenciesSatisfied(goal: Goal, step: GoalStep): Boolean =
        step.dependsOn.all { dependencyId ->
            goal.steps.firstOrNull { it.id == dependencyId }?.status == GoalStepStatus.COMPLETED
        }

    private fun validateDependencies(steps: List<GoalStep>) {
        val ids = steps.map { it.id }.toSet()
        require(steps.all { step -> step.dependsOn.all(ids::contains) }) {
            "Une dépendance référence une étape inconnue."
        }

        val graph = steps.associate { it.id to it.dependsOn }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()

        fun visit(id: String) {
            if (id in visiting) error("Dépendance cyclique détectée.")
            if (id in visited) return
            visiting += id
            graph[id].orEmpty().forEach(::visit)
            visiting -= id
            visited += id
        }

        steps.forEach { visit(it.id) }
    }

    private fun updateGoal(goal: Goal): Goal {
        goals[goal.id] = goal
        return goal
    }

    private fun updateGoalStatus(goal: Goal, status: GoalStatus): Goal =
        updateGoal(goal.copy(status = status))

    private fun requireGoal(id: String): Goal =
        goals[id] ?: error("Tâche inconnue : $id")
}
