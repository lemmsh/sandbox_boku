package com.lemmsh.boku

import io.grpc.stub.StreamObserver
import com.lemmsh.boku.Transfer.*


import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap


object UserOperations {
    val activeOperations = mutableMapOf<String, Mutex>()
}



class MoneyTransferServiceImpl(private val clientBalanceManager: ClientBalanceManager) : MoneyTransferServiceGrpc.MoneyTransferServiceImplBase() {

    private val latestStatusMap: ConcurrentHashMap<UUID, OperationStatus> = ConcurrentHashMap()
    private val statusChannel: Channel<OperationStatus> = clientBalanceManager.statusChannel

    fun start() {
        runBlocking {
            launch {
                statusChannel.consumeAsFlow().collect { status ->
                    latestStatusMap[status.requestId] = status
                    if (status.complete) {
                        latestStatusMap.remove(status.requestId)
                    }
                }
            }
        }
    }

    override fun sendMoneyToUser(request: SendMoneyToUserRequest, responseObserver: StreamObserver<StatusUpdate>) {
        val requestId = UUID.randomUUID()
        clientBalanceManager.transferMoney(requestId, request.fromNickname, request.to.nickname, Money.fromProto(request.amount)) //todo: support phones

        runBlocking {
            launch {

                val initialStatus = latestStatusMap[requestId]
                if (initialStatus != null) {
                    responseObserver.onNext(StatusUpdate.newBuilder()
                        .setProgressUpdate(ProgressUpdate.newBuilder().setOperationId(
                            requestId.toString()
                        ).setMessage(initialStatus.)))
                }

                statusChannel.consumeAsFlow().collect { status ->
                    if (status.requestId == requestId) {
                        val response = StatusUpdateResponse(status.message, status.progress, status.isCompleted)
                        responseObserver.onNext(response)
                        if (status.isCompleted) {
                            responseObserver.onCompleted()
                        }
                    }
                }
            }
        }
    }
}




class DummyMoneyTransferServiceImpl : MoneyTransferServiceGrpc.MoneyTransferServiceImplBase() {

    override fun sendMoneyToUser(
        request: SendMoneyToUserRequest,
        responseObserver: StreamObserver<StatusUpdate>
    ) {
        handleOperation(request.fromNickname, responseObserver)
    }

    override fun sendMoneyToExternal(
        request: SendMoneyToExternalRequest,
        responseObserver: StreamObserver<StatusUpdate>
    ) {
        handleOperation(request.fromNickname, responseObserver)
    }

    private fun handleOperation(nickname: String, responseObserver: StreamObserver<StatusUpdate>) {
        GlobalScope.launch(Dispatchers.IO) {
            val mutex = UserOperations.activeOperations.getOrPut(nickname) { Mutex() }
            if (mutex.isLocked) {
                val update = StatusUpdate.newBuilder()
                    .setConcurrentlyExecutingOperation(
                        ConcurrentlyExecutingOperation.newBuilder()
                            .setOtherOperationId("someOtherOperationId")
                            .setMessage("Another operation is already in progress")
                            .setProgress(0)
                            .setIsCompleted(false)
                            .build()
                    )
                    .build()
                responseObserver.onNext(update)
                responseObserver.onCompleted()
            } else {
                mutex.withLock {
                    sendDummyStatusUpdates(responseObserver)
                }
            }
        }
    }

    private fun sendDummyStatusUpdates(responseObserver: StreamObserver<StatusUpdate>) {
        for (i in 1..10) {
            val progressUpdate = ProgressUpdate.newBuilder()
                .setOperationId("dummyOperationId")
                .setMessage("In Progress")
                .setProgress(i * 10)
                .setIsCompleted(false)
                .build()
            val statusUpdate = StatusUpdate.newBuilder()
                .setProgressUpdate(progressUpdate)
                .build()
            responseObserver.onNext(statusUpdate)
        }
        val finalProgressUpdate = ProgressUpdate.newBuilder()
            .setOperationId("dummyOperationId")
            .setMessage("Completed")
            .setProgress(100)
            .setIsCompleted(true)
            .build()
        val finalStatusUpdate = StatusUpdate.newBuilder()
            .setProgressUpdate(finalProgressUpdate)
            .build()
        responseObserver.onNext(finalStatusUpdate)
        responseObserver.onCompleted()
    }
}