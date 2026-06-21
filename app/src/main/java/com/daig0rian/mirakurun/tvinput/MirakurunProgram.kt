package com.daig0rian.mirakurun.tvinput

data class MirakurunProgram(
    val id: Long,
    val serviceId: Int,
    val networkId: Int,
    val startAtMillis: Long,
    val durationMillis: Long,
    val name: String,
    val description: String?,
)
