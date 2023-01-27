// Copyright (c) 2022 Gitpod GmbH. All rights reserved.
// Licensed under the GNU Affero General Public License (AGPL).
// See License.AGPL.txt in the project root for license information.

package io.gitpod.jetbrains.remote

import com.intellij.codeWithMe.ClientId
import com.intellij.ide.BrowserUtil
import com.intellij.ide.CliResult
import com.intellij.ide.CommandLineProcessor
import com.intellij.ide.impl.ProjectUtil.getActiveProject
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.client.ClientKind
import com.intellij.openapi.client.ClientSessionsManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.LowMemoryWatcher
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.wm.IdeFrame
import com.intellij.remoteDev.util.onTerminationOrNow
import com.intellij.ui.ComponentUtil
import com.intellij.util.application
import com.intellij.util.net.ssl.CertificateManager
import com.intellij.util.proxy.CommonProxy
import com.intellij.util.withFragment
import com.intellij.util.withPath
import com.intellij.util.withQuery
import com.jetbrains.rd.framework.util.launch
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.lifetime.isNotAlive
import git4idea.config.GitVcsApplicationSettings
import io.gitpod.gitpodprotocol.api.GitpodClient
import io.gitpod.gitpodprotocol.api.GitpodServerLauncher
import io.gitpod.gitpodprotocol.api.entities.RemoteTrackMessage
import io.gitpod.jetbrains.remote.services.HeartbeatService
import io.gitpod.jetbrains.remote.utils.LocalHostUri
import io.gitpod.jetbrains.remote.utils.Retrier.retry
import io.gitpod.supervisor.api.*
import io.gitpod.supervisor.api.Info.WorkspaceInfoResponse
import io.gitpod.supervisor.api.Notification.*
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import io.grpc.stub.ClientCallStreamObserver
import io.grpc.stub.ClientResponseObserver
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.Counter
import io.prometheus.client.Gauge
import io.prometheus.client.exporter.PushGateway
import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import kotlinx.coroutines.guava.asDeferred
import org.jetbrains.ide.BuiltInServerManager
import java.awt.KeyboardFocusManager
import java.beans.PropertyChangeEvent
import java.net.URI
import java.net.URL
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Duration
import java.util.*
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import javax.websocket.DeploymentException


@Suppress("UnstableApiUsage", "OPT_IN_USAGE")
@Service
class GitpodManager : Disposable {

    companion object {
        // there should be only one channel per an application to avoid memory leak
        val supervisorChannel: ManagedChannel = ManagedChannelBuilder.forTarget("localhost:22999").usePlaintext().build()
    }

    val devMode = System.getenv("JB_DEV").toBoolean()
    private val backendKind = System.getenv("JETBRAINS_GITPOD_BACKEND_KIND") ?: "unknown"
    private val backendQualifier = System.getenv("JETBRAINS_BACKEND_QUALIFIER") ?: "unknown"

    private val lifetime = Lifetime.Eternal.createNested()

    override fun dispose() {
        lifetime.terminate()
    }

    val registry = CollectorRegistry()

    init {
        // Rate of low memory after GC notifications in the last 5 minutes:
        // rate(gitpod_jb_backend_low_memory_after_gc_total[5m])
        val lowMemoryCounter = Counter.build()
                .name("gitpod_jb_backend_low_memory_after_gc")
                .help("Low memory notifications after GC")
                .labelNames("product", "qualifier")
                .register(registry)
        LowMemoryWatcher.register({
            lowMemoryCounter.labels(backendKind, backendQualifier).inc()
        }, LowMemoryWatcher.LowMemoryWatcherType.ONLY_AFTER_GC, this)
    }

    init {
        val monitoringJob = GlobalScope.launch {
            if (application.isHeadlessEnvironment) {
                return@launch
            }
            val pg = if (devMode) null else PushGateway("localhost:22999")
            // Heap usage at any time in the last 5 minutes:
            // max_over_time(gitpod_jb_backend_memory_used_bytes[5m:])/max_over_time(gitpod_jb_backend_memory_max_bytes[5m:])
            val allocatedGauge = Gauge.build()
                    .name("gitpod_jb_backend_memory_max_bytes")
                    .help("Total allocated memory of JB backend in bytes.")
                    .labelNames("product", "qualifier")
                    .register(registry)
            val usedGauge = Gauge.build()
                    .name("gitpod_jb_backend_memory_used_bytes")
                    .help("Used memory of JB backend in bytes.")
                    .labelNames("product", "qualifier")
                    .register(registry)

            while (isActive) {
                val totalMemory = Runtime.getRuntime().totalMemory()
                allocatedGauge.labels(backendKind, backendQualifier).set(totalMemory.toDouble())
                val usedMemory = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                usedGauge.labels(backendKind, backendQualifier).set(usedMemory.toDouble())
                try {
                    pg?.push(registry, "jb_backend")
                } catch (t: Throwable) {
                    thisLogger().error("gitpod: failed to push monitoring metrics:", t)
                }
                delay(5000)
            }
        }
        lifetime.onTerminationOrNow { monitoringJob.cancel() }
    }

    init {
        GlobalScope.launch {
            if (application.isHeadlessEnvironment) {
                return@launch
            }
            try {
                val backendPort = BuiltInServerManager.getInstance().waitForStart().port
                val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build()
                val httpRequest = HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:24000/gatewayLink?backendPort=$backendPort"))
                        .GET()
                        .build()
                val response =
                        httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() == 200) {
                    val gatewayLink = response.body()
                    thisLogger().warn(
                            "\n\n\n*********************************************************\n\n" +
                                    "Gitpod gateway link: $gatewayLink" +
                                    "\n\n*********************************************************\n\n\n"
                    )
                } else {
                    throw Exception("" + response.statusCode())
                }
            } catch (t: Throwable) {
                thisLogger().error("gitpod: failed to resolve gateway link:", t)
            }
        }
    }

    init {
        GitVcsApplicationSettings.getInstance().isUseCredentialHelper = true
    }

    init {
        if (!application.isHeadlessEnvironment) {
            registerActiveNotifications()
        }
    }

    private fun registerActiveNotifications() {
        val keyboardFocusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()

        var activeProject: Project? = null
        var activeLifetime = Lifetime.Terminated.createNested()
        val lock = Object()
        val updateActiveNotifications = {
            synchronized(lock) {
                var project = getActiveProject()
                if (project == null || project.isDefault || project.isDisposed || !project.isInitialized) {
                    project = null
                }
                if (project != null) {
                    val session = ClientSessionsManager.getProjectSessions(project, ClientKind.REMOTE).firstOrNull()
                    if (session == null) {
                        project = null
                    }
                }

                if (project != activeProject) {
                    activeLifetime.terminate()
                }
                activeProject = project
                if (activeLifetime.isNotAlive && activeProject != null) {
                    activeLifetime = observeActiveNotifications(activeProject!!)
                }
            }
        }
        updateActiveNotifications()
        val listener = { _: PropertyChangeEvent -> updateActiveNotifications() }
        keyboardFocusManager.addPropertyChangeListener("activeWindow", listener)
        lifetime.onTerminationOrNow {
            keyboardFocusManager.removePropertyChangeListener("activeWindow", listener)
            activeLifetime.terminate()
        }
    }

    val gitpodPortForwardingService = serviceOrNull<GitpodPortForwardingService>()
    private fun observeActiveNotifications(project: Project): LifetimeDefinition {
        val jobLifetime = lifetime.createNested()
        val job = jobLifetime.launch {
            val notifications = NotificationServiceGrpc.newStub(supervisorChannel)
            val futureNotifications = NotificationServiceGrpc.newFutureStub(supervisorChannel)
            while (isActive) {
                try {
                    val f = CompletableFuture<Void>()
                    notifications.subscribeActive(
                            SubscribeActiveRequest.newBuilder().build(),
                            object : ClientResponseObserver<SubscribeActiveRequest, SubscribeActiveResponse> {

                                override fun beforeStart(requestStream: ClientCallStreamObserver<SubscribeActiveRequest>) {
                                    jobLifetime.onTerminationOrNow {
                                        requestStream.cancel("terminated", null)
                                    }
                                }

                                override fun onNext(n: SubscribeActiveResponse) {
                                    GlobalScope.launch {
                                        val request = n.request
                                        try {
                                            val session = ClientSessionsManager.getProjectSessions(project, ClientKind.REMOTE).firstOrNull()
                                            if (session != null) {
                                                ClientId.withClientId(session.clientId) {
                                                    if (request.preview != null) {
                                                        var resolvedUrl = request.preview.url
                                                        val uri = URI.create(request.preview.url)
                                                        val localHostUriMetadata = LocalHostUri.extractLocalHostUriMetaDataForPortMapping(uri)
                                                        if (localHostUriMetadata.isPresent && gitpodPortForwardingService != null) {
                                                            var localHostUriFromPort = Optional.empty<URI>()
                                                            application.invokeAndWait {
                                                                localHostUriFromPort = gitpodPortForwardingService
                                                                        .getLocalHostUriFromHostPort(localHostUriMetadata.get().port)
                                                            }
                                                            if (localHostUriFromPort.isPresent) {
                                                                resolvedUrl = localHostUriFromPort.get()
                                                                        .withPath(uri.path)
                                                                        .withQuery(uri.query)
                                                                        .withFragment(uri.fragment)
                                                                        .toString()
                                                            }
                                                        }
                                                        BrowserUtil.browse(resolvedUrl, project)
                                                    } else if (request.open != null) {
                                                        val futures = ArrayList<Deferred<CliResult>>()
                                                        for (path in request.open.getPathsList()) {
                                                            try {
                                                                val file = parseFilePath(path)
                                                                futures.add(CommandLineProcessor.doOpenFileOrProject(file, request.open.await).future)
                                                            } catch (t: Throwable) {
                                                                thisLogger().error("gitpod: failed to open '" + path + "': ", t)
                                                            }
                                                        }
                                                        futures.awaitAll()
                                                    }
                                                }
                                            }
                                        } catch (t: Throwable) {
                                            thisLogger().error("gitpod: failed to process active request: ", t)
                                        }
                                        futureNotifications.notifyActiveRespond(
                                                NotifyActiveRespondRequest.newBuilder()
                                                        .setRequestId(n.requestId)
                                                        .setResponse(NotifyActiveResponse.newBuilder().build())
                                                        .build()
                                        )
                                    }
                                }

                                override fun onError(t: Throwable) {
                                    f.completeExceptionally(t)
                                }

                                override fun onCompleted() {
                                    f.complete(null)
                                }

                            })
                    f.await()
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        throw t
                    }
                    thisLogger().error("gitpod: failed to stream active notifications: ", t)
                }
                delay(1000L)
            }
        }
        jobLifetime.onTerminationOrNow {
            job.cancel()
        }
        return jobLifetime
    }
    private fun parseFilePath(path: String): Path {
        return try {
            var file: Path = Path.of(FileUtilRt.toSystemDependentName(path)) // handle paths like '/file/foo\qwe'
            if (!file.isAbsolute) {
                file = file.toAbsolutePath()
            }
            file.normalize()
        } catch (e: InvalidPathException) {
            throw Exception("failed to parse file path", e)
        }
    }

    val notificationGroup = NotificationGroupManager.getInstance().getNotificationGroup("Gitpod Notifications")
    private val notificationsJob = GlobalScope.launch {
        if (application.isHeadlessEnvironment) {
            return@launch
        }
        val notifications = NotificationServiceGrpc.newStub(supervisorChannel)
        val futureNotifications = NotificationServiceGrpc.newFutureStub(supervisorChannel)
        while (isActive) {
            try {
                val f = CompletableFuture<Void>()
                notifications.subscribe(
                        SubscribeRequest.newBuilder().build(),
                        object : ClientResponseObserver<SubscribeRequest, SubscribeResponse> {

                            override fun beforeStart(requestStream: ClientCallStreamObserver<SubscribeRequest>) {
                                lifetime.onTerminationOrNow {
                                    requestStream.cancel("disposed", null)
                                }
                            }

                            override fun onNext(n: SubscribeResponse) {
                                val request = n.request
                                val type = when (request.level) {
                                    NotifyRequest.Level.ERROR -> NotificationType.ERROR
                                    NotifyRequest.Level.WARNING -> NotificationType.WARNING
                                    else -> NotificationType.INFORMATION
                                }
                                val notification = notificationGroup.createNotification(request.message, type)
                                for (action in request.actionsList) {
                                    notification.addAction(NotificationAction.createSimpleExpiring(action) {
                                        futureNotifications.respond(
                                                RespondRequest.newBuilder()
                                                        .setRequestId(n.requestId)
                                                        .setResponse(NotifyResponse.newBuilder().setAction(action).build())
                                                        .build()
                                        )
                                    })
                                }
                                notification.notify(null)
                            }

                            override fun onError(t: Throwable) {
                                f.completeExceptionally(t)
                            }

                            override fun onCompleted() {
                                f.complete(null)
                            }
                        })
                f.await()
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    throw t
                }
                thisLogger().error("gitpod: failed to stream notifications: ", t)
            }
            delay(1000L)
        }
    }

    init {
        lifetime.onTerminationOrNow {
            notificationsJob.cancel()
        }
    }

    var infoResponse: WorkspaceInfoResponse? = null
    val pendingInfo = CompletableFuture<WorkspaceInfoResponse>()

    private val infoJob = GlobalScope.launch {
        if (application.isHeadlessEnvironment) {
            return@launch
        }
        try {
            // TODO(ak) replace retry with proper handling of grpc errors
            infoResponse = retry(3) {
                InfoServiceGrpc
                        .newFutureStub(supervisorChannel)
                        .workspaceInfo(Info.WorkspaceInfoRequest.newBuilder().build())
                        .asDeferred()
                        .await()
            }
            pendingInfo.complete(infoResponse)
        } catch (t: Throwable) {
            pendingInfo.completeExceptionally(t)
        }
    }

    init {
        lifetime.onTerminationOrNow {
            infoJob.cancel()
        }
    }

    val client = GitpodClient()
    private val serverJob = GlobalScope.launch {
        val info = pendingInfo.await()

        // TODO(ak) replace retry with proper handling of grpc errors
        val tokenResponse = retry(3) {
            val request = Token.GetTokenRequest.newBuilder()
                    .setHost(info.gitpodApi.host)
                    .addScope("function:openPort")
                    .addScope("function:sendHeartBeat")
                    .addScope("function:setWorkspaceTimeout")
                    .addScope("function:stopWorkspace")
                    .addScope("function:takeSnapshot")
                    .addScope("function:trackEvent")
                    .setKind("gitpod")
                    .build()

            TokenServiceGrpc
                    .newFutureStub(supervisorChannel)
                    .getToken(request)
                    .asDeferred()
                    .await()
        }

        val launcher = GitpodServerLauncher.create(client)
        val plugin = PluginManagerCore.getPlugin(PluginId.getId("io.gitpod.jetbrains.remote"))!!
        val connect = {
            val originalClassLoader = Thread.currentThread().contextClassLoader
            try {
                val proxies = CommonProxy.getInstance().select(URL(info.gitpodHost))
                val sslContext = CertificateManager.getInstance().sslContext

                // see https://intellij-support.jetbrains.com/hc/en-us/community/posts/360003146180/comments/360000376240
                Thread.currentThread().contextClassLoader = HeartbeatService::class.java.classLoader

                launcher.listen(
                        info.gitpodApi.endpoint,
                        info.gitpodHost,
                        plugin.pluginId.idString,
                        plugin.version,
                        tokenResponse.token,
                        proxies,
                        sslContext
                )
            } finally {
                Thread.currentThread().contextClassLoader = originalClassLoader
            }
        }

        val minReconnectionDelay = 2 * 1000L
        val maxReconnectionDelay = 30 * 1000L
        val reconnectionDelayGrowFactor = 1.5
        var reconnectionDelay = minReconnectionDelay
        val gitpodHost = info.gitpodApi.host
        var closeReason: Any = "cancelled"
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                try {
                    val connection = connect()
                    thisLogger().info("$gitpodHost: connected")
                    reconnectionDelay = minReconnectionDelay
                    closeReason = connection.await()
                    thisLogger().warn("$gitpodHost: connection closed, reconnecting after $reconnectionDelay milliseconds: $closeReason")
                } catch (t: Throwable) {
                    if (t is DeploymentException) {
                        // connection is alright, but server does not want to handshake, there is no point to try with the same token again
                        throw t
                    }
                    closeReason = t
                    thisLogger().warn(
                            "$gitpodHost: failed to connect, trying again after $reconnectionDelay milliseconds:",
                            closeReason
                    )
                }
                delay(reconnectionDelay)
                closeReason = "cancelled"
                reconnectionDelay = (reconnectionDelay * reconnectionDelayGrowFactor).toLong()
                if (reconnectionDelay > maxReconnectionDelay) {
                    reconnectionDelay = maxReconnectionDelay
                }
            }
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                closeReason = t
            }
        }
        thisLogger().warn("$gitpodHost: connection permanently closed: $closeReason")
    }

    init {
        lifetime.onTerminationOrNow {
            serverJob.cancel()
        }
    }

    private val versionName = ApplicationInfo.getInstance().versionName
    private val fullVersion = ApplicationInfo.getInstance().fullVersion

    fun trackEvent(eventName: String, props: Map<String, Any?>) {
        val timestamp = System.currentTimeMillis()
        GlobalScope.launch {
            val info = pendingInfo.await()
            val event = RemoteTrackMessage().apply {
                event = eventName
                properties = mapOf(
                        "instanceId" to info.instanceId,
                        "workspaceId" to info.workspaceId,
                        "appName" to versionName,
                        "appVersion" to fullVersion,
                        "timestamp" to timestamp,
                        "product" to backendKind,
                        "qualifier" to backendQualifier
                ).plus(props)
            }
            if (devMode) {
                thisLogger().warn("gitpod: $event")
            } else {
                client.server.trackEvent(event)
            }
        }
    }

    var resourceStatus: Status.ResourcesStatusResponse? = null

    private val metricsJob = GlobalScope.launch {
        if (application.isHeadlessEnvironment) {
            return@launch
        }
        val status = StatusServiceGrpc.newFutureStub(supervisorChannel)
        while (isActive) {
            try {
                val f = status.resourcesStatus(Status.ResourcesStatuRequest.getDefaultInstance())
                resourceStatus = f.asDeferred().await()
            } catch (t: Throwable) {
                if (t is CancellationException) {
                    throw t
                }
                thisLogger().error("gitpod: failed to retrieve resource status: ", t)
            }
            delay(1000L)
        }
    }

    init {
        lifetime.onTerminationOrNow {
            metricsJob.cancel()
        }
    }

    /** Opens the give URL in the Browser and records an event indicating it was open from a custom IntelliJ Action. */
    fun openUrlFromAction(url: String) {
        trackEvent("jb_execute_command_gitpod_open_link", mapOf("url" to url))
        BrowserUtil.browse(url)
    }
}
