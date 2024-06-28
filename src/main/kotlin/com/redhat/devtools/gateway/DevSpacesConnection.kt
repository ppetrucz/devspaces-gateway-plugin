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
package com.redhat.devtools.gateway

import com.redhat.devtools.gateway.openshift.DevWorkspaces
import com.redhat.devtools.gateway.openshift.Pods
import com.redhat.devtools.gateway.server.RemoteServer
import com.jetbrains.gateway.thinClientLink.LinkedClientManager
import com.jetbrains.gateway.thinClientLink.ThinClientHandle
import com.jetbrains.rd.util.lifetime.Lifetime
import io.kubernetes.client.openapi.ApiException
import java.io.IOException
import java.net.URI

class DevSpacesConnection(private val devSpacesContext: DevSpacesContext) {
    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    fun connect(
        onConnected: () -> Unit,
        onDisconnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
    ): ThinClientHandle {
        println("Breakpoint 4.1")
        if (devSpacesContext.isConnected)
            throw IOException(String.format("Already connected to %s", devSpacesContext.devWorkspace.metadata.name))

        devSpacesContext.isConnected = true
        try {
            println("Breakpoint 4.2")
            return doConnection(onConnected, onDevWorkspaceStopped, onDisconnected)
        } catch (e: Exception) {
            devSpacesContext.isConnected = false
            println("Error during connection: $e")
            throw e
        }
    }

    @Throws(Exception::class)
    @Suppress("UnstableApiUsage")
    private fun doConnection(
        onConnected: () -> Unit,
        onDevWorkspaceStopped: () -> Unit,
        onDisconnected: () -> Unit
    ): ThinClientHandle {
        startAndWaitDevWorkspace()
        println("Breakpoint 4.3")

        val remoteServer = RemoteServer(devSpacesContext).also {
            it.waitProjectsReady()
            println("Breakpoint 4.4")
        }
        val projectStatus = remoteServer.getProjectStatus()
        println("Breakpoint 4.5")

        val client = LinkedClientManager
            .getInstance()
            .startNewClient(
                Lifetime.Eternal,
                URI(projectStatus.joinLink),
                "",
                onConnected
            )
        println("Breakpoint 4.6")

        val forwarder = Pods(devSpacesContext.client).forward(remoteServer.pod, 5990, 5990)

        println("Breakpoint 4.7")

        client.run {
            println("Breakpoint 4.8")
            lifetime.onTermination { forwarder.close() }
            println("Breakpoint 4.9")
            lifetime.onTermination {
                println("Breakpoint 4.10")
                if (remoteServer.waitProjectsTerminated())
                    println("Breakpoint 4.11")
                    DevWorkspaces(devSpacesContext.client)
                        .stop(
                            devSpacesContext.devWorkspace.metadata.namespace,
                            devSpacesContext.devWorkspace.metadata.name
                        )
                        .also { onDevWorkspaceStopped() }
                println("Breakpoint 4.12")
            }
            lifetime.onTermination { devSpacesContext.isConnected = false }
            println("Breakpoint 4.13")
            lifetime.onTermination(onDisconnected)
        }
        println("Breakpoint 4.14")
        return client
    }

    @Throws(IOException::class, ApiException::class)
    private fun startAndWaitDevWorkspace() {
        if (!devSpacesContext.devWorkspace.spec.started) {
            DevWorkspaces(devSpacesContext.client)
                .start(
                    devSpacesContext.devWorkspace.metadata.namespace,
                    devSpacesContext.devWorkspace.metadata.name
                )
        }

        if (!DevWorkspaces(devSpacesContext.client)
                .waitPhase(
                    devSpacesContext.devWorkspace.metadata.namespace,
                    devSpacesContext.devWorkspace.metadata.name,
                    DevWorkspaces.RUNNING,
                    DevWorkspaces.RUNNING_TIMEOUT
                )
        ) throw IOException(
            String.format(
                "DevWorkspace '%s' is not running after %d seconds",
                devSpacesContext.devWorkspace.metadata.name,
                DevWorkspaces.RUNNING_TIMEOUT
            )
        )
    }
}
