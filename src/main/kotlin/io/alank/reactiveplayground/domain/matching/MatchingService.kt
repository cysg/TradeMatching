package io.alank.reactiveplayground.domain.matching

import io.alank.reactiveplayground.domain.account.AccountGroup
import io.alank.reactiveplayground.domain.account.AccountProperties
import io.alank.reactiveplayground.domain.marketdata.MarketDataService
import io.alank.reactiveplayground.domain.trade.*
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux

@Service
class MatchingService(private val tradeRepository: TradeRepository,
                      private val marketDataService: MarketDataService,
                      private val accountProperties: AccountProperties) {

    fun match(): Flux<MatchingResult> {
        val trades = tradeRepository
                .findAll()
                // Using ConnectedFlux as a valve to "rally" or wait for all accountGroups subscribe, then emit
                .publish()
                .autoConnect(accountProperties.groups.size)
        return Flux.fromIterable(accountProperties.groups)
                .flatMap { ag ->
                    trades
                            .filter { it.isInGroup(ag) }
                            // match for every instrument
                            .groupBy { it.ticker }
                            .flatMap { instrumentGroup ->
                                val ticker = instrumentGroup.key()!!
                                val initial = InstrumentMatching(accountGroup = ag, ticker = ticker)
                                instrumentGroup
                                        .map<TradeEvent> { BuySellTradeEvent(it) }
                                        .concatWith(marketDataService.getPrice(ticker)
                                                .map { EndOfTradeStreamEvent(it) })
                                        .scan(initial) { matchingInProgress, event -> matchingInProgress.handle(event) }
                            }
                            .flatMap { Flux.fromIterable(it.results) }
                }
    }

    private fun Trade.isInGroup(accountGroup: AccountGroup): Boolean =
            accountGroup.accounts.isEmpty() or accountGroup.accounts.contains(this.account)
}


