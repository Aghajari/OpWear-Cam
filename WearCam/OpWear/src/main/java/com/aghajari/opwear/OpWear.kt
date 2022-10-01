package com.aghajari.opwear

import android.content.Context
import androidx.lifecycle.*
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * A utility class to communicate between an Android phone and WearOS.
 * One of the devices is considering as observer and another one is observable.
 * Only observer needs to call <code>OpWear.connect()</code> for creating the connection.
 * Both devices need to prepare with calling <code>OpWear.install(Context)</code>.
 * Context should be an instance of [LifecycleOwner].
 *
 *
 * @author AmirHossein Aghajari
 */
@Suppress("MemberVisibilityCanBePrivate")
object OpWear {

    private const val ACKNOWLEDGEMENT_REQUEST_KEY = "OP_WEAR_ACKNOWLEDGEMENT_REQ"
    private const val ACKNOWLEDGEMENT_VALIDATION_KEY = "OP_WEAR_ACKNOWLEDGEMENT_VALID"
    private const val ACKNOWLEDGEMENT_RESPONSE_KEY = "OP_WEAR_ACKNOWLEDGEMENT_RES"

    private var applicationContext: Context? = null

    // tries and waits for response of observable
    // to the acknowledgement request.
    var maxConnectionTry = 5
    var acknowledgementDuration = 1.toDuration(DurationUnit.SECONDS)

    // 0 = none, 1 = accepted, 2 = rejected
    private var acknowledgement = 0

    // Used to validate acknowledgement response is
    // from the one we requested
    private var acknowledgementNode: String? = null

    var connectionStatus = ConnectionStatus.DISCONNECTED
        private set

    var connectedNodeId: String? = null
        private set

    var connectedNodeDisplayName: String? = null
        private set

    var connectionListener: OnConnectionChangedListener? = null

    /**
     * Observable notifies this listener whenever
     * an acknowledge request received.
     * it should return true to accept the request,
     * false to reject the request.
     */
    var acknowledgeListener: OnAcknowledgeListener? = null

    /**
     * @see MessageClient.OnMessageReceivedListener
     */
    var messageReceivedListener: MessageClient.OnMessageReceivedListener? = null

    /**
     * @see AutoValidationStrategy.NONE
     * @see AutoValidationStrategy.ACKNOWLEDGE
     * @see AutoValidationStrategy.RESPONSE
     * @see autoValidationDuration
     * @see validationLifecycleOwner
     */
    var autoValidationStrategy = AutoValidationStrategy.NONE
        set(value) {
            field = value
            startAutoValidation()
        }

    /**
     * **AutoValidationStrategy.ACKNOWLEDGE:**
     * sends acknowledgment request at the specified time interval.
     *
     *
     * **AutoValidationStrategy.RESPONSE:**
     * must receive message within the specified time.
     */
    var autoValidationDuration = 1.toDuration(DurationUnit.SECONDS)

    /**
     * launches and repeats autoValidation coroutine
     * on this lifecycleScope
     */
    var validationLifecycleOwner: LifecycleOwner? = null

    // Used in AutoValidationStrategy.RESPONSE
    private var lastMessageTimeMillis = 0L

    // Used to cancel last job whenever
    // strategy or connection's status changed.
    private var autoValidationJob: Job? = null

    enum class ConnectionStatus {
        /**
         * Connected successfully
         */
        CONNECTED,

        /**
         * Trying to connect
         */
        CONNECTING,

        /**
         * Haven't called <code>OpWear.connect()</code> yet
         * Or observable haven't accepted any acknowledgment request.
         */
        DISCONNECTED,

        /**
         * Couldn't connect to any node
         */
        FAILED_TO_CONNECT,

        /**
         * A connection found but observable rejected
         * the observer's acknowledgment request!
         */
        REJECTED,

        /**
         * A connection found but observable didn't response to
         * the observer's acknowledgment request!
         */
        NO_RESPONSE
    }

    enum class AutoValidationStrategy {
        /**
         * No auto validation provided.
         */
        NONE,

        /**
         * Sends acknowledgment request at the specified time
         * interval to make sure the connection is still alive.
         */
        ACKNOWLEDGE,

        /**
         * Must receive a message within the specified time.
         * Useful for observable real-time connections.
         */
        RESPONSE
    }

    interface OnConnectionChangedListener {
        fun onConnectionChange(status: ConnectionStatus)
    }

    interface OnAcknowledgeListener {
        fun onAcknowledgeRequest(nodeId: String, displayName: String): Boolean
    }

    private val messageListener = MessageClient.OnMessageReceivedListener {
        lastMessageTimeMillis = System.currentTimeMillis()

        when (it.path) {
            ACKNOWLEDGEMENT_REQUEST_KEY -> {
                val name = String(it.data)
                val response: Boolean? =
                    acknowledgeListener?.onAcknowledgeRequest(it.sourceNodeId, name)

                if (response != false) {
                    connectedNodeId = it.sourceNodeId
                    connectedNodeDisplayName = name
                    notifyConnection(ConnectionStatus.CONNECTED)
                }

                val out = ByteArray(1)
                out[0] = if (response != false) 1 else 0

                Wearable.getMessageClient(applicationContext!!).sendMessage(
                    it.sourceNodeId,
                    ACKNOWLEDGEMENT_RESPONSE_KEY,
                    out,
                    MessageOptions(MessageOptions.MESSAGE_PRIORITY_HIGH)
                )
            }
            ACKNOWLEDGEMENT_VALIDATION_KEY -> {
                val out = ByteArray(1)
                out[0] = if (it.sourceNodeId == connectedNodeId) 1 else 0

                Wearable.getMessageClient(applicationContext!!).sendMessage(
                    it.sourceNodeId,
                    ACKNOWLEDGEMENT_RESPONSE_KEY,
                    out,
                    MessageOptions(MessageOptions.MESSAGE_PRIORITY_HIGH)
                )
            }
            ACKNOWLEDGEMENT_RESPONSE_KEY -> {
                if (it.sourceNodeId == acknowledgementNode)
                    acknowledgement = if (it.data.size != 1 || it.data[0] == 0.toByte()) 2 else 1
            }
            else -> {
                if (it.sourceNodeId != connectedNodeId || !isConnected()) {
                    if (isConnected())
                        return@OnMessageReceivedListener

                    CoroutineScope(Dispatchers.IO).launch {
                        val name = findName(it.sourceNodeId)

                        val response: Boolean? =
                            acknowledgeListener?.onAcknowledgeRequest(it.sourceNodeId, name)

                        if (response != false) {
                            connectedNodeId = it.sourceNodeId
                            connectedNodeDisplayName = name
                            notifyConnection(ConnectionStatus.CONNECTED)
                        } else
                            return@launch

                        withContext(Dispatchers.Main) {
                            messageReceivedListener?.onMessageReceived(it)
                        }
                    }
                } else {
                    messageReceivedListener?.onMessageReceived(it)
                }
            }
        }
    }

    private val lifecycleObserver: LifecycleEventObserver =
        LifecycleEventObserver { source, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> unregister()
                Lifecycle.Event.ON_DESTROY -> {
                    detachFromLifecycle(source)
                }
                else -> {}
            }
        }

    /**
     * Prepares OpWear.
     *
     * Calls [attachToLifecycle] if the context is a [LifecycleOwner]
     */
    fun install(context: Context) {
        applicationContext = context.applicationContext

        if (context is OnConnectionChangedListener) {
            connectionListener = context
            notifyConnection()
        }

        if (context is OnAcknowledgeListener)
            acknowledgeListener = context

        if (context is MessageClient.OnMessageReceivedListener)
            messageReceivedListener = context

        if (context is LifecycleOwner)
            attachToLifecycle(context)
    }

    /**
     * Starts communicating with a connected node.
     * Only observer should call this.
     */
    suspend fun connect() {
        if (connectionStatus == ConnectionStatus.CONNECTING)
            return

        notifyConnection(ConnectionStatus.CONNECTING)

        withContext(Dispatchers.IO) {
            val name =
                Tasks.await(Wearable.getNodeClient(applicationContext!!).localNode).displayName
            val task = Wearable.getNodeClient(applicationContext!!).connectedNodes
            val nodes = Tasks.await(task)

            var noResponseNode: Node? = null

            for (node in nodes) {
                val status = validateConnection(
                    ACKNOWLEDGEMENT_REQUEST_KEY,
                    node.id,
                    name
                )
                acknowledgementNode = null

                if (status == ConnectionStatus.CONNECTED || status == ConnectionStatus.REJECTED) {
                    if (status == ConnectionStatus.CONNECTED) {
                        connectedNodeId = node.id
                        connectedNodeDisplayName = node.displayName
                    }
                    notifyConnection(status)
                    return@withContext
                } else if (status == ConnectionStatus.NO_RESPONSE) {
                    noResponseNode = node
                }
            }

            if (noResponseNode == null) {
                notifyConnection(ConnectionStatus.FAILED_TO_CONNECT)
            } else {
                connectedNodeId = noResponseNode.id
                connectedNodeDisplayName = noResponseNode.displayName
                notifyConnection(ConnectionStatus.NO_RESPONSE)
            }
        }
    }

    /**
     * Connects to a lifecycle
     */
    fun attachToLifecycle(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(lifecycleObserver)

        if (validationLifecycleOwner == null)
            validationLifecycleOwner = owner

        if (connectionListener == null && owner is OnConnectionChangedListener) {
            connectionListener = owner
            notifyConnection()
        }

        if (acknowledgeListener == null && owner is OnAcknowledgeListener)
            acknowledgeListener = owner

        if (messageReceivedListener == null && owner is MessageClient.OnMessageReceivedListener)
            messageReceivedListener = owner
    }

    /**
     * Destroys every related objects to this lifecycle.
     */
    fun detachFromLifecycle(owner: LifecycleOwner) {
        owner.lifecycle.removeObserver(lifecycleObserver)

        if (connectionListener == owner)
            connectionListener = null

        if (acknowledgeListener == owner)
            acknowledgeListener = null

        if (messageReceivedListener == owner)
            messageReceivedListener = null

        if (validationLifecycleOwner == owner)
            validationLifecycleOwner = null
    }

    /**
     * Registers [MessageClient.OnMessageReceivedListener].
     */
    fun register() {
        Wearable.getMessageClient(applicationContext!!)
            .addListener(messageListener)
        notifyConnection()
    }

    /**
     * Unregisters [MessageClient.OnMessageReceivedListener].
     */
    fun unregister() {
        Wearable.getMessageClient(applicationContext!!)
            .removeListener(messageListener)
        autoValidationJob?.cancel()
    }

    /**
     * @return true if [connectionStatus] equals to [ConnectionStatus.CONNECTED].
     * doesn't validate the connection!
     */
    fun isConnected(): Boolean = connectionStatus == ConnectionStatus.CONNECTED

    /**
     * @see [MessageClient.sendMessage]
     * @return true if message sent successfully, false otherwise.
     */
    suspend fun sendMessage(path: String, data: ByteArray? = null): Boolean {
        if (!isConnected())
            return false

        return withContext(Dispatchers.IO) {
            try {
                Tasks.await(
                    Wearable.getMessageClient(applicationContext!!)
                        .sendMessage(connectedNodeId!!, path, data)
                )
                return@withContext true
            } catch (e: Exception) {
                notifyConnection(ConnectionStatus.FAILED_TO_CONNECT)
                return@withContext false
            }
        }
    }

    /**
     * Validates current connection by sending
     * an acknowledgment validation request.
     *
     * @return true if connection is alive, false otherwise
     */
    suspend fun validateConnection(): Boolean {
        if (!isConnected())
            return false

        val oldStatus = connectionStatus
        connectionStatus = validateConnection(
            ACKNOWLEDGEMENT_VALIDATION_KEY,
            connectedNodeId!!,
            connectedNodeDisplayName
        )
        acknowledgementNode = null

        if (oldStatus != connectionStatus)
            notifyConnection()

        return isConnected()
    }

    /**
     * Finds the specified node's [Node.getDisplayName]
     */
    private suspend fun findName(nodeId: String): String {
        return withContext(Dispatchers.IO) {
            val nodes = Tasks.await(Wearable.getNodeClient(applicationContext!!).connectedNodes)

            for (node in nodes) {
                if (node.id == nodeId)
                    return@withContext node.displayName
            }

            return@withContext "UNKNOWN"
        }
    }

    /**
     * Updates [connectionStatus] and notifies [connectionListener]
     */
    private fun notifyConnection(status: ConnectionStatus? = null) {
        if (status != null)
            connectionStatus = status

        connectionListener?.onConnectionChange(connectionStatus)
        startAutoValidation()
    }

    /**
     * Validates connection with a node
     * by sending acknowledgement request
     */
    private suspend fun validateConnection(
        key: String,
        nodeId: String,
        name: String?
    ): ConnectionStatus {
        return withContext(Dispatchers.IO) {
            try {
                var sleepTime = 100L
                acknowledgement = 0
                acknowledgementNode = nodeId
                val step =
                    (acknowledgementDuration.inWholeMilliseconds - sleepTime * maxConnectionTry) / ((maxConnectionTry * (maxConnectionTry - 1)) / 2)

                Tasks.await(
                    Wearable.getMessageClient(applicationContext!!).sendMessage(
                        nodeId,
                        key,
                        name?.toByteArray(),
                        MessageOptions(MessageOptions.MESSAGE_PRIORITY_HIGH)
                    )
                )

                try {
                    for (i in 1..maxConnectionTry) {
                        if (acknowledgement != 0)
                            return@withContext if (acknowledgement == 1) ConnectionStatus.CONNECTED else ConnectionStatus.REJECTED

                        if (i != maxConnectionTry)
                            delay(sleepTime)
                        sleepTime += step
                    }
                } catch (ignore: Exception) {
                }
            } catch (e: Exception) {
                return@withContext ConnectionStatus.FAILED_TO_CONNECT
            }
            return@withContext ConnectionStatus.NO_RESPONSE
        }
    }

    /**
     * Checks and starts auto validation on [validationLifecycleOwner]
     * based on the [autoValidationStrategy]
     */
    private fun startAutoValidation() {
        autoValidationJob?.cancel()
        if (isConnected() && autoValidationStrategy != AutoValidationStrategy.NONE)
            autoValidationJob = validationLifecycleOwner?.lifecycleScope?.launch(Dispatchers.IO) {
                validationLifecycleOwner?.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    delay(autoValidationDuration)
                    while (isConnected()) {
                        if (autoValidationStrategy == AutoValidationStrategy.RESPONSE) {
                            if (System.currentTimeMillis() - lastMessageTimeMillis
                                > autoValidationDuration.inWholeMilliseconds * 1.5
                            )
                                notifyConnection(ConnectionStatus.NO_RESPONSE)
                        } else if (autoValidationStrategy == AutoValidationStrategy.ACKNOWLEDGE) {
                            delay(autoValidationDuration)
                            validateConnection()
                        }
                    }
                }
            }
    }
}