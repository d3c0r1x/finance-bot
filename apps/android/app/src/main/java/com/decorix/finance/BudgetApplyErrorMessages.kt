package com.decorix.finance

internal object BudgetApplyErrorMessages {
    fun message(status: Int, language: String): String? = when (status) {
        412 -> if (language == "ru") {
            "Предложение устарело. Можно оставить текущие лимиты или создать новое предложение в разделе «Бюджеты»."
        } else {
            "This proposal is stale. Keep the current limits or create a new proposal in Budgets."
        }
        409 -> if (language == "ru") {
            "Предложение недоступно. Можно оставить текущие лимиты или создать новое предложение в разделе «Бюджеты»."
        } else {
            "This proposal is unavailable. Keep the current limits or create a new proposal in Budgets."
        }
        403 -> if (language == "ru") {
            "У вас нет прав применять это предложение бюджета."
        } else {
            "You do not have permission to apply this budget proposal."
        }
        else -> null
    }
}
