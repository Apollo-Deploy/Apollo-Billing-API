package com.apollodeploy.billing.infrastructure.iam

import com.apollodeploy.oauth.m2m.ktor.machinePrincipal
import io.ktor.server.application.ApplicationCall

/** Compatibility accessor for audit identity populated by the SDK route guard. */
fun ApplicationCall.authenticatedClientId(): String? = runCatching { machinePrincipal().clientId.value }.getOrNull()
