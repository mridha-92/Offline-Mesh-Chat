package com.example.database

import androidx.room.TypeConverter
import com.example.core.model.DeliveryStatus
import com.example.core.model.TransportType

class Converters {
    @TypeConverter
    fun fromTransportType(value: TransportType?): String = value?.name ?: TransportType.AUTO.name

    @TypeConverter
    fun toTransportType(value: String?): TransportType =
        runCatching { TransportType.valueOf(value ?: "") }.getOrDefault(TransportType.AUTO)

    @TypeConverter
    fun fromDeliveryStatus(value: DeliveryStatus?): String = value?.name ?: DeliveryStatus.QUEUED.name

    @TypeConverter
    fun toDeliveryStatus(value: String?): DeliveryStatus =
        runCatching { DeliveryStatus.valueOf(value ?: "") }.getOrDefault(DeliveryStatus.QUEUED)
}
