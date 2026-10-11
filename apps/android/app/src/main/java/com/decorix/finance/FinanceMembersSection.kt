package com.decorix.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun FinanceMembersSection(
    members: List<FinanceTenantMember>,
    language: String,
    modifier: Modifier = Modifier,
    onSelect: (FinanceTenantMember) -> Unit,
) {
    val russian = language == "ru"
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (russian) "Участники" else "Members", style = MaterialTheme.typography.headlineSmall)
        if (members.isEmpty()) {
            Text(if (russian) "Участники не найдены." else "No members found.")
        } else {
            members.forEach { member ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(member.displayName, style = MaterialTheme.typography.titleMedium)
                        Button(modifier = Modifier.testTag("member-navigation-${member.userId}"),
                            onClick = { onSelect(member) }) {
                            Text(if (russian) "Показать операции" else "View transactions")
                        }
                    }
                }
            }
        }
    }
}
