package com.example.model

enum class AutoSelectStrategy(
    val title: String,
    val subtitle: String,
    val persianTitle: String
) {
    LOWEST_PING(
        title = "Lowest Latency",
        subtitle = "Auto-switches to node with the lowest round-trip ping",
        persianTitle = "حداقل پینگ"
    ),
    MAXIMUM_SPEED(
        title = "Maximum Speed",
        subtitle = "Prioritizes UDP/QUIC (Hysteria 2 / VLESS) high-bandwidth routes",
        persianTitle = "حداکثر سرعت"
    ),
    LOWEST_PACKET_LOSS(
        title = "Zero Drop / Stability",
        subtitle = "Prioritizes nodes with 0% packet loss and minimum jitter",
        persianTitle = "کمترین قطعی و افت بسته"
    )
}
