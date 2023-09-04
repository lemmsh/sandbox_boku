import com.boku.withdrawal.WithdrawalService
import com.lemmsh.boku.OperationStatus
import com.lemmsh.boku.Transfer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.channels.Channel

// Assume this is your Java WithdrawalService interface
class WithdrawalServiceWrapper(private val withdrawalService: WithdrawalService, private val statusChannel: Channel<OperationStatus>) {



    fun requestWithdrawal(id: UUID, address: String, amount: Transfer.Money) {
        val withdrawalId = WithdrawalService.WithdrawalId(id)
        val withdrawalAddress = WithdrawalService.Address(address)

        withdrawalService.requestWithdrawal(withdrawalId, withdrawalAddress, amount)

        runBlocking {
            launch {
                val startTime = Instant.now()
                while (true) {
                    val withdrawalState = withdrawalService.getRequestState(withdrawalId)
                    val elapsedTime = Instant.now().toEpochMilli() - startTime.toEpochMilli()
                    val percentage = (elapsedTime / 100).toInt()

                    val operationStatus = OperationStatus(
                        requestId = id,
                        complete = withdrawalState != WithdrawalService.WithdrawalState.PROCESSING,
                        success = withdrawalState == WithdrawalService.WithdrawalState.COMPLETED,
                        message = withdrawalState.name,
//                        progress = percentage,
                    )

                    statusChannel.send(operationStatus)

                    if (withdrawalState == WithdrawalService.WithdrawalState.COMPLETED || withdrawalState == WithdrawalService.WithdrawalState.FAILED) {
                        break
                    }

                    delay(1000)  // Wait for 1 second before the next update
                }
            }
        }
    }
}