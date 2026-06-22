package com.example.moexmvp

/** Единая симуляция бэктеста: prod-like sizing (как «Тест страт.»), без схемы notional×leverage. */
internal fun defaultProdLikeSimOptions(): ZStrategySimOptions =
    ZStrategySimOptions(slippageSpreadPts = DEFAULT_STRATEGY_TEST_SLIPPAGE_SPREAD_PTS)

internal fun prodLikeSizingForAccount(
    accountSizeRub: Double,
    capitalUsagePercent: Double = DEFAULT_STRATEGY_TEST_CAPITAL_USAGE_PERCENT,
    leverageForLots: Double = 7.0,
): ZStrategyProdLikeSizing = ZStrategyProdLikeSizing(
    accountSizeRub = accountSizeRub,
    capitalUsagePercent = capitalUsagePercent,
    leverageForLots = leverageForLots,
)

internal fun prepareMoexPointsForProdLikeSim(raw: List<DataPoint>): List<DataPoint> =
    prepareM15PointsForZStrategySignalDetection(raw)

internal fun buildProdLikeStrategySimMetrics(
    points: List<DataPoint>,
    thresholds: DynamicThresholds,
    accountSizeRub: Double = DEFAULT_STRATEGY_TEST_ACCOUNT_RUB,
    capitalUsagePercent: Double = DEFAULT_STRATEGY_TEST_CAPITAL_USAGE_PERCENT,
    leverageForLots: Double = 7.0,
    commissionPercentPerSide: Double = 0.04,
    periodDescription: String = "prod-like sim",
    compoundReturns: Boolean = false,
    exitMode: ZStrategyExitMode = ZStrategyExitMode.FixedThreshold,
    zPeakTrailZ: Double = DEFAULT_STRATEGY_TEST_Z_PEAK_TRAIL,
    entryPullbackZ: Double = 0.0,
    simOptions: ZStrategySimOptions = defaultProdLikeSimOptions(),
    simLoopStartIndex: Int = 1,
    initialCarryOpen: PortfolioOpenPosition? = null,
    todaySliceOnly: Boolean = false,
): PortfolioMetrics? = buildZStrategyPortfolioMetrics(
    points = points,
    thresholds = thresholds,
    notionalRub = accountSizeRub,
    leverage = 1.0,
    commissionPercentPerSide = commissionPercentPerSide,
    periodDescription = periodDescription,
    compoundReturns = compoundReturns,
    exitMode = exitMode,
    zPeakTrailZ = zPeakTrailZ,
    entryPullbackZ = entryPullbackZ,
    simOptions = simOptions,
    simLoopStartIndex = simLoopStartIndex,
    initialCarryOpen = initialCarryOpen,
    todaySliceOnly = todaySliceOnly,
    prodLikeSizing = prodLikeSizingForAccount(
        accountSizeRub = accountSizeRub,
        capitalUsagePercent = capitalUsagePercent,
        leverageForLots = leverageForLots,
    ),
)
