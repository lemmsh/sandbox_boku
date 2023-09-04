package com.lemmsh.boku

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import java.util.UUID

data class Money(val amount: Double, val currency: String) {
    companion object{
        fun fromProto(money: Transfer.Money) = Money(money.amount, money.currency)

    }
}

data class ClientKey(val nickname: String, val currency: String)

data class OperationStatus(val requestId: UUID, val complete: Boolean, val message: String, val success: Boolean? = null)

data class TransferRequest(val requestId: UUID, val from: String, val to: String, val money: Money)

class ClientBalanceManager {

    private val clientBalances: MutableMap<ClientKey, Money> = mutableMapOf()
    private val requestChannel = Channel<TransferRequest>(Channel.UNLIMITED)
    val statusChannel = Channel<OperationStatus>(Channel.UNLIMITED)

    // Explicitly create a single-threaded dispatcher
    private val singleThreadDispatcher = newSingleThreadContext("BalanceManagerThread")

    fun start(): Channel<OperationStatus> {
        // Process all requests in a single thread using Channels API and singleThreadDispatcher
        runBlocking {
            launch(singleThreadDispatcher) {
                for (request in requestChannel) {
                    // Send status that the processing started
                    val startedStatus = OperationStatus(request.requestId, false, "Processing started")
                    statusChannel.trySend(startedStatus)

                    // Perform the money transfer
                    val fromKey = ClientKey(request.from, request.money.currency)
                    val toKey = ClientKey(request.to, request.money.currency)
                    val fromBalance = clientBalances.getOrDefault(fromKey, Money(0.0, request.money.currency))
                    val toBalance = clientBalances.getOrDefault(toKey, Money(0.0, request.money.currency))

                    if (fromBalance.amount >= request.money.amount) {
                        clientBalances[fromKey] = Money(fromBalance.amount - request.money.amount, request.money.currency)
                        clientBalances[toKey] = Money(toBalance.amount + request.money.amount, request.money.currency)
                        statusChannel.trySend(OperationStatus(request.requestId, true, "Transfer successful", true))
                    } else {
                        statusChannel.trySend(OperationStatus(request.requestId, true, "Insufficient funds", false))
                    }
                }
            }
        }
        return statusChannel
    }

    fun transferMoney(requestId: UUID, from: String, to: String, money: Money) {
        // Send status that the processing is queued
        val queuedStatus = OperationStatus(requestId, false, "Processing is queued")
        statusChannel.trySend(queuedStatus)
        requestChannel.trySend(TransferRequest(requestId, from, to, money))
    }
}