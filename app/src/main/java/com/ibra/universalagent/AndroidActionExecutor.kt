package com.ibra.universalagent

import android.content.Context
import android.content.Intent
import java.util.Locale

class AndroidActionExecutor(
    private val context: Context
) : ActionExecutor {

    override fun execute(step: PlanStep): ActionExecutorResult {
        return when (step.action) {
            PlanAction.OPEN_APP -> openApp(step.target)
            else -> ActionExecutorResult(
                success = false,
                message = "Action Android non encore implémentée : " + step.action
            )
        }
    }

    private fun openApp(target: String?): ActionExecutorResult {
        val requested = target?.trim()?.lowercase(Locale.ROOT)
        if (requested.isNullOrEmpty()) {
            return ActionExecutorResult(false, "Je ne connais pas l'application à ouvrir.")
        }

        val packageManager = context.packageManager
        val candidates = packageManager.getInstalledApplications(0)

        val app = candidates.firstOrNull { info ->
            val packageName = info.packageName.lowercase(Locale.ROOT)
            val label = packageManager.getApplicationLabel(info).toString().lowercase(Locale.ROOT)
            packageName == requested || label == requested ||
                packageName.substringAfterLast('.').replace('_', ' ') == requested ||
                label.contains(requested)
        }

        if (app == null) {
            return ActionExecutorResult(false, "Application introuvable : $target")
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(app.packageName)
            ?: return ActionExecutorResult(false, "Impossible d'ouvrir l'application : $target")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(launchIntent)
            ActionExecutorResult(true, "Application ouverte : " + packageManager.getApplicationLabel(app))
        } catch (error: Exception) {
            ActionExecutorResult(false, "Échec d'ouverture de l'application : $target")
        }
    }
}
