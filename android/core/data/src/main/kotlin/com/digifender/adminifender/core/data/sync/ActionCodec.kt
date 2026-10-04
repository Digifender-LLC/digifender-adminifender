package com.digifender.adminifender.core.data.sync

import com.digifender.adminifender.core.model.ActionKind
import com.digifender.adminifender.core.model.ConcernAction
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.digifender.adminifender.core.data.network.wireJson

/** Stores a queued action exactly, so a resend after process death is the same request body. */
object ActionCodec {
    fun encode(action: ConcernAction): Pair<String, String> {
        val args: JsonObject = when (action) {
            is ConcernAction.Snooze -> buildJsonObject { put("untilMs", action.untilMs) }
            is ConcernAction.Wait -> buildJsonObject {
                put("note", action.note?.let(::JsonPrimitive) ?: JsonNull)
                put("awaitedParty", action.awaitedParty?.let(::JsonPrimitive) ?: JsonNull)
                put("followUpAtMs", action.followUpAtMs?.let(::JsonPrimitive) ?: JsonNull)
            }
            is ConcernAction.SetDue -> buildJsonObject {
                put("atMs", action.atMs?.let(::JsonPrimitive) ?: JsonNull)
                put("zone", action.zone?.let(::JsonPrimitive) ?: JsonNull)
                put("sourceText", action.sourceText?.let(::JsonPrimitive) ?: JsonNull)
            }
            else -> JsonObject(emptyMap())
        }
        return action.kind.wire to args.toString()
    }

    fun decode(kind: String, argsJson: String): ConcernAction {
        val args = wireJson.parseToJsonElement(argsJson).jsonObject
        fun text(name: String) = args[name]?.jsonPrimitive?.contentOrNull
        fun long(name: String) = args[name]?.jsonPrimitive?.longOrNull
        return when (ActionKind.entries.first { it.wire == kind }) {
            ActionKind.ACKNOWLEDGE -> ConcernAction.Acknowledge
            ActionKind.RESOLVE -> ConcernAction.Resolve
            ActionKind.REOPEN -> ConcernAction.Reopen
            ActionKind.MARK_READ -> ConcernAction.MarkRead
            ActionKind.SNOOZE -> ConcernAction.Snooze(long("untilMs") ?: error("untilMs"))
            ActionKind.WAIT -> ConcernAction.Wait(text("note"), text("awaitedParty"), long("followUpAtMs"))
            ActionKind.SET_DUE -> ConcernAction.SetDue(long("atMs"), text("zone"), text("sourceText"))
        }
    }
}
