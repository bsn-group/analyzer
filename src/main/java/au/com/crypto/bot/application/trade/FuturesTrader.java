package au.com.crypto.bot.application.trade;

import au.com.crypto.bot.application.ApplicationControllers;
import au.com.crypto.bot.application.CONSTANTS;
import au.com.crypto.bot.application.analyzer.entities.*;
import au.com.crypto.bot.application.utils.JSONHelper;
import au.com.crypto.bot.application.utils.LoadConfigs;
import au.com.crypto.bot.application.utils.Utils;
import com.google.gson.Gson;
import serilogj.Log;

import javax.transaction.Transactional;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Map;

/**
 * Futures trade
 */
public class FuturesTrader extends TraderImpl {

    Gson gson = new Gson();
    int leverage = 10;
    //int quantity = 1; //Asset quantity

    public FuturesTrader() {
    }

    @Override
    @Transactional
    public void raiseSignal(ApplicationControllers ac, long existingSignalId, double price, String symbol,
                            String tradeType,
                            String positionType, Strategies.ConditionsGroup conditionsGroup, String strategyPairName,
                            Map<String, String> props, String market, int contracts, long exchangeId, long marketEventId, String exchangeName) {
        long signalId = existingSignalId;
        if (existingSignalId == 0L) {
            signalId = saveFutureSignal(ac, symbol, positionType, strategyPairName, exchangeId);
            Log.information("{@Application} Successfully saved a signal @{Symbol} with signal id @{SignalId}", "Analyzer", symbol, signalId, tradeType);
        }
        saveFutureSignalCommand(ac, signalId, conditionsGroup.getConditionsName(), symbol, price, tradeType, conditionsGroup, market, contracts, props, marketEventId, exchangeName);
        Log.information("{@Application} Successfully added command @{Symbol} with signal id @{SignalId} to {TradeType}", "Analyzer", symbol, signalId, tradeType);
    }

    private void saveFutureSignalCommand(ApplicationControllers ac, long signalId, String strategyPairName,
                                         String symbol, double price, String tradeType,
                                         Strategies.ConditionsGroup conditionsGroup, String market, int eventContracts, Map<String, String> props, long marketEventId, String exchangeName) {
        FuturesSignalCommandController fscController = ac.getFuturesSignalCommandController();
        FuturesSignalCommand fsCommand = new FuturesSignalCommand();
        fsCommand.setSignalId(signalId);
        fsCommand.setStrategyName(strategyPairName);
        fsCommand.setStrategyHash(JSONHelper.jsonToHash(gson.toJson(conditionsGroup)));
        fsCommand.setStrategyData(gson.toJson(conditionsGroup));
        fsCommand.setStatus(CONSTANTS._created);
        fsCommand.setRequestDateTime(new Date());
        fsCommand.setLeverage(leverage);
        fsCommand.setPrice(new BigDecimal(price));
        fsCommand.setMarketEventId(marketEventId);
        fsCommand.setSignalAction(tradeType); //OPEN, CLOSE, INCREASE ..
        if (tradeType.equalsIgnoreCase(CONSTANTS._close)) {
            fsCommand.setQuantity(BigDecimal.valueOf(getActiveContracts(ac, signalId)));
            if (fsCommand.getQuantity().doubleValue() != 0) {
                fscController.save(fsCommand);
                Log.information("{@Application} Successfully saved for @{Symbol} with signal id @{SignalId} " +
                        "with Contracts From Active Position {Contracts}" +
                        "with Position Type {PositionType}" +
                        "with Strategy name {Strategy}" +
                        "with contract multiplier {Multiplier}" +
                        "with marketEventId {MarketEventId}" +
                        "with Leverage {Leverage}", "Analyzer", symbol, signalId, fsCommand.getQuantity(), tradeType, strategyPairName, marketEventId, leverage);
                try {
                    Utils.triggerTrader(props.get("traderUrl"));
                } catch (Exception e) {
                    Log.error(e, "Error Raising http signal to trader");
                }
            } else {
                Log.error("WRONG SIGNAL OR NO CONTRACTS - Symbol: {Symbol} - Contracts: {Contracts} ", symbol, getDefaultContractsFromDB(ac, symbol, exchangeName));
            }
        }
        if (eventContracts <= 0 && fsCommand.getQuantity().doubleValue() != 0) {
            double contracts = getDefaultContractsFromDB(ac, symbol, exchangeName);
            if (contracts != 0) {
                fsCommand.setQuantity(BigDecimal.valueOf(
                        getContractsByMarket(market, contracts, price)
                ));
                fscController.save(fsCommand);

                Log.information("{@Application} Successfully saved for @{Symbol} with signal id @{SignalId} " +
                        "with Contracts {Contracts}" +
                        "with Position Type {PositionType}" +
                        "with Strategy name {Strategy}" +
                        "with contract multiplier {Multiplier}" +
                        "with marketEventId {MarketEventId}" +
                        "with Leverage {Leverage}", "Analyzer", symbol, signalId, contracts, tradeType, strategyPairName, marketEventId, leverage);
                try {
                    Utils.triggerTrader(props.get("traderUrl"));
                } catch (Exception e) {
                    Log.error(e, "Error Raising http signal to trader");
                }
            } else {
                Log.error("WRONG SYMBOL OR NO CONTRACTS - Symbol: {Symbol} - Contracts: {Contracts} ", symbol, getDefaultContractsFromDB(ac, symbol, exchangeName));
            }
        } else {
            fsCommand.setQuantity(BigDecimal.valueOf(
                    getContractsByMarket(market, eventContracts, price)
            ));
            fscController.save(fsCommand);

            Log.information("{@Application} Successfully saved for @{Symbol} with signal id @{SignalId} " +
                    "with Contracts {Contracts}" +
                    "with Position Type {PositionType}" +
                    "with Strategy name {Strategy}" +
                    "with Leverage {Leverage}", "Analyzer", symbol, signalId, eventContracts, tradeType, strategyPairName, leverage);
            try {
                Utils.triggerTrader(props.get("traderUrl"));
            } catch (Exception e) {
                Log.error(e, "Error Raising http signal to trader");
            }
        }
    }

    private double getContractsByMarket(String market, double contracts, double price) {
        double contractsPerType = market.equalsIgnoreCase("USDT") ? contracts : (contracts * price) / 10;
        int scale = 4;
        return BigDecimal.valueOf(contractsPerType).setScale(scale, BigDecimal.ROUND_HALF_UP).doubleValue();
    }

    private double getActiveContracts(ApplicationControllers ac, long signalId) {
        return ac.getFuturesSignalController().getActiveContractsFromDB(signalId);
    }

    private long saveFutureSignal(ApplicationControllers ac, String symbol, String positionType, String strategyPairName, long exchangeId) {
        FuturesSignal fs = new FuturesSignal();
        fs.setExchangeId(exchangeId);
        fs.setCreatedDateTime(new Date());
        fs.setStrategyPairName(strategyPairName);
        fs.setPositionType(positionType);
        fs.setSymbol(symbol);
        FuturesSignalController fsc = ac.getFuturesSignalController();
        long signalId = fsc.save(fs);
        Log.information("{@Application} Successfully saved a future signal @{Symbol} signal id @{SignalId}", "Analyzer", symbol, signalId);
        return signalId;
    }

    private Double getDefaultContractsFromDB(ApplicationControllers ac, String symbol, String exchangeName) {
        double returnValue = 0d;
        try {
            Map<String, String> contracts = LoadConfigs.getConfig(ac, exchangeName);
            if (contracts.containsKey(symbol))
                returnValue = Double.parseDouble(contracts.get(symbol));
        } catch (Exception e) {
            Log.error(e, "Error in parsing contract config value, check config table, expecting a double");
        }
        return returnValue;
    }

    private Double getContractMultiplierFromDB(ApplicationControllers ac, String symbol) {
        double returnValue = 1d;
        try {
            Map<String, String> contracts = LoadConfigs.getConfig(ac, CONSTANTS._contractsMultiplier);
            if (contracts.containsKey(symbol))
                returnValue = Double.parseDouble(contracts.get(symbol));
        } catch (Exception e) {
            Log.error(e, "Error in parsing contract config value, check config table, expecting a double");
        }
        return returnValue;
    }
}
