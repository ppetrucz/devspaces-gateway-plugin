/*
 * Copyright (c) 2024 Red Hat, Inc.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *   Red Hat, Inc. - initial API and implementation
 */
package com.redhat.devtools.gateway.view.steps

import com.redhat.devtools.gateway.DevSpacesBundle
import com.redhat.devtools.gateway.DevSpacesContext
import com.redhat.devtools.gateway.openshift.OpenShiftClientFactory
import com.redhat.devtools.gateway.openshift.Projects
import com.redhat.devtools.gateway.settings.DevSpacesSettings
import com.redhat.devtools.gateway.view.InformationDialog
import com.google.gson.Gson
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import io.kubernetes.client.openapi.ApiClient
import io.kubernetes.client.openapi.ApiException
import io.kubernetes.client.openapi.auth.ApiKeyAuth
import io.kubernetes.client.util.Config
import java.util.logging.Logger

private val logger = Logger.getLogger("DevSpacesOpenShiftConnectionStepView")

class DevSpacesOpenShiftConnectionStepView(private var devSpacesContext: DevSpacesContext) : DevSpacesWizardStep {
    private var tfServer = JBTextField("")
    private var tfToken = JBTextField()

    private var settingsAreLoaded = false
    private val settings = service<DevSpacesSettings>()

    override val nextActionText = DevSpacesBundle.message("connector.wizard_step.openshift_connection.button.next")
    override val previousActionText =
        DevSpacesBundle.message("connector.wizard_step.openshift_connection.button.previous")
    override val component = panel {
        row {
            label(DevSpacesBundle.message("connector.wizard_step.openshift_connection.title")).applyToComponent {
                font = JBFont.h2().asBold()
            }
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.server")) {
            cell(tfServer).align(Align.FILL)
        }
        row(DevSpacesBundle.message("connector.wizard_step.openshift_connection.label.token")) {
            cell(tfToken).align(Align.FILL)
        }
    }.apply {
        background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
        border = JBUI.Borders.empty(8)
    }

    override fun onInit() {
        loadOpenShiftConnectionSettings()
    }

    override fun onPrevious(): Boolean {
        return true
    }

    override fun onNext(): Boolean {
        val client = OpenShiftClientFactory().create(tfServer.text, tfToken.text.toCharArray())
        testConnection(client)

        saveOpenShiftConnectionSettings()
        devSpacesContext.client = client

        return true
    }

    private fun testConnection(client: ApiClient) {
        try {
            Projects(client).list()
        } catch (e: Exception) {
            logger.severe("Exception caught during connection test: ${e::class.simpleName}")
            logger.severe("Exception message: ${e.message}")
            
            var errorMsg = e.message ?: "Unknown error"
            if (e is ApiException) {
                logger.severe("API Exception details:")
                logger.severe("Response body: ${e.responseBody}")
                logger.severe("Response code: ${e.code}")
                
                val response = try {
                    logger.info("Attempting to parse response body as JSON")
                    Gson().fromJson(e.responseBody, Map::class.java)
                } catch (jsonException: Exception) {
                    logger.severe("Failed to parse response body: ${jsonException.message}")
                    null
                }
                
                errorMsg = when {
                    response != null -> {
                        logger.info("Response parsed successfully")
                        logger.info("Response content: $response")
                        when {
                            response["message"] is String -> {
                                logger.info("Message found in response")
                                String.format("Reason: %s", response["message"] as String)
                            }
                            else -> {
                                logger.warning("No 'message' field found in response or it's not a String")
                                "Error response doesn't contain expected 'message' field"
                            }
                        }
                    }
                    else -> {
                        logger.warning("Failed to parse error response")
                        "Failed to parse error response"
                    }
                }
            }
    
            logger.severe("Final error message: $errorMsg")
            InformationDialog("Connection failed", errorMsg, component).show()
            throw e
        }
    }
    
    

    private fun loadOpenShiftConnectionSettings() {
        // load from kubeconfig first
        try {
            val config = Config.defaultClient()
            tfServer.text = config.basePath

            val auth = config.authentications["BearerToken"]
            if (auth is ApiKeyAuth) tfToken.text = auth.apiKey
        } catch (e: Exception) {
            // Do nothing
        }

        // then from settings
        if (tfServer.text.isEmpty() || tfToken.text.isEmpty()) {
            tfServer.text = settings.state.server.orEmpty()
            tfToken.text = settings.state.token.orEmpty()
            settingsAreLoaded = true
        }
    }

    private fun saveOpenShiftConnectionSettings() {
        if (settingsAreLoaded) {
            settings.state.server = tfServer.text
            settings.state.token = tfToken.text
        }
    }
}