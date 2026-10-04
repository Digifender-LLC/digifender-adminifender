package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.ChangeDto
import com.digifender.adminifender.core.data.network.ChangesPageDto
import com.digifender.adminifender.core.data.network.SnapshotEntityDto
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

fun contractFile(path: String): String = File(System.getProperty("contractsDir") ?: error("contractsDir"), path).readText()

fun concernJson(id: String, revision: Int, status: String = "open", title: String = "Invoice"): JsonObject = Json.parseToJsonElement(
    """{"id":"$id","revision":$revision,"title":"$title","status":"$status","importance":"routine","criticalBasis":null,"serviceId":null,
       "readAt":null,"acknowledgedAt":null,"resolvedAt":null,"snoozeUntil":null,"waiting":null,"due":null,"sourceIds":[],
       "updatedAt":"2026-10-03T12:00:00.000Z"}""",
) as JsonObject

fun concernChange(seq: Long, id: String, revision: Int, status: String = "open") = ChangeDto(seq, "concern", id, revision, false, concernJson(id, revision, status))

fun page(vararg changes: ChangeDto, hasMore: Boolean = false) = ChangesPageDto(changes.toList(), changes.lastOrNull()?.seq ?: 0, hasMore)

fun snapshot(id: String, revision: Int) = SnapshotEntityDto("concern", id, revision, concernJson(id, revision))

