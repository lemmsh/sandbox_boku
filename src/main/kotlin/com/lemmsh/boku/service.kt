package com.lemmsh.boku

import WithdrawalServiceWrapper
import io.grpc.stub.StreamObserver
import com.lemmsh.boku.Transfer.*


import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap



class MoneyTransferServiceImpl(
    private val clientBalanceManager: ClientBalanceManager,
    private val statusChannel: Channel<OperationStatus>
) : MoneyTransferServiceGrpc.MoneyTransferServiceImplBase() {

    private val latestStatusMap: ConcurrentHashMap<UUID, OperationStatus> = ConcurrentHashMap()

    fun start() {
        runBlocking {
            launch {
                statusChannel.consumeAsFlow().collect { status ->
                    latestStatusMap[status.requestId] = status
                }
            }
        }
    }

    override fun sendMoneyToExternal(
        request: SendMoneyToExternalRequest,
        responseObserver: StreamObserver<StatusUpdate>
    ) {

    }

    override fun sendMoneyToUser(request: SendMoneyToUserRequest, responseObserver: StreamObserver<StatusUpdate>) {
        val requestId = UUID.randomUUID()
        //todo: support phones
        clientBalanceManager.transferMoney(requestId, request.from.nickname, request.to.nickname, Money.fromProto(request.amount))
        sendUpdateStream(requestId, responseObserver)
    }

    fun sendUpdateStream(requestId: UUID, responseObserver: StreamObserver<StatusUpdate>) {
        runBlocking {
            launch {

                val initialStatus = latestStatusMap[requestId]
                if (initialStatus != null) {
                    //responseObserver.onNext()
                    if (initialStatus.complete) {
                        responseObserver.onCompleted()
                        latestStatusMap.remove(requestId)
                    }
                }
                //todo: we can miss an update here, better to start listening to the channel before reading the last value,
                // and then compare the last value with those we've already sent by a seq num
                statusChannel.consumeAsFlow().collect { status ->
                    if (status.requestId == requestId) {
//                        val response = StatusUpdateResponse(status.message, status.progress, status.isCompleted)
//                        responseObserver.onNext(response)
                        if (status.complete) {
                            responseObserver.onCompleted()
                            latestStatusMap.remove(requestId)
                        }
                    }
                }
            }
        }
    }


}


