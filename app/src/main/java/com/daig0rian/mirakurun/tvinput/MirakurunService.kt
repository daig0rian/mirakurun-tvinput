package com.daig0rian.mirakurun.tvinput

data class MirakurunService(
    val id: Long,
    val serviceId: Int,
    val networkId: Int,
    val name: String,
    val channelType: String,       // "GR" | "BS" | "CS" | "SKY" など
    val serviceType: Int,          // 1=デジタルTV, 2=デジタルラジオ, 0xA0=データ放送など
    val remoteControlKeyId: Int,   // リモコンチャンネル番号（0 = 未設定）
) {
    fun displayNumber(): String = if (remoteControlKeyId > 0) {
        remoteControlKeyId.toString()
    } else {
        serviceId.toString()
    }
}
