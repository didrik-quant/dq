package com.didrikquant.replay.storage

import mu.KotlinLogging
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

private val logger = KotlinLogging.logger {}

public class ChronicleEventStore(
    private val dataDir: Path,
    private val retentionDays: Int = 30,
) : Closeable {

    private var currentDate: LocalDate = LocalDate.now()
    private var outputStream: DataOutputStream? = null
    private var currentFile: Path? = null

    init {
        Files.createDirectories(dataDir)
    }

    @Synchronized
    public fun append(event: RecordedEvent) {
        val eventDate = Instant.ofEpochMilli(event.eventTimestamp)
            .atZone(ZoneOffset.UTC)
            .toLocalDate()

        if (eventDate != currentDate || outputStream == null) {
            closeCurrentFile()
            currentDate = eventDate
            openNewFile()
        }

        writeEvent(event)
    }

    private fun openNewFile() {
        val filename = "${currentDate.format(DateTimeFormatter.ISO_LOCAL_DATE)}.dat"
        currentFile = dataDir.resolve(filename)
        val fos = Files.newOutputStream(currentFile, java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.APPEND)
        outputStream = DataOutputStream(BufferedOutputStream(fos, 65536))
        logger.debug { "Opened event file: $currentFile" }
    }

    private fun writeEvent(event: RecordedEvent) {
        val out = outputStream ?: return

        out.writeLong(event.wallTimestamp)
        out.writeLong(event.eventTimestamp)
        out.writeLong(event.sequence)
        out.writeUTF(event.eventType)
        out.writeInt(event.payload.size)
        out.write(event.payload)
    }

    private fun closeCurrentFile() {
        outputStream?.flush()
        outputStream?.close()
        outputStream = null
        currentFile = null
    }

    public fun createTailer(): EventTailer = EventTailer(dataDir)

    public fun cleanup() {
        RetentionManager.cleanup(dataDir, retentionDays)
    }

    override fun close() {
        closeCurrentFile()
    }
}

public class EventTailer(private val dataDir: Path) {

    private var files: List<Path> = emptyList()
    private var currentFileIndex: Int = 0
    private var currentReader: RandomAccessFile? = null
    private var nextEvent: RecordedEvent? = null

    public fun seekToStart() {
        closeCurrentReader()
        files = getSortedDataFiles()
        currentFileIndex = 0
        nextEvent = null
        if (files.isNotEmpty()) {
            openFile(0)
        }
    }

    public fun seekToDate(date: LocalDate) {
        closeCurrentReader()
        files = getSortedDataFiles()

        val dateStr = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
        currentFileIndex = files.indexOfFirst { it.name >= "$dateStr.dat" }

        if (currentFileIndex < 0) {
            currentFileIndex = files.size
        }

        nextEvent = null
        if (currentFileIndex < files.size) {
            openFile(currentFileIndex)
        }
    }

    private fun getSortedDataFiles(): List<Path> {
        if (!dataDir.exists()) return emptyList()
        return dataDir.listDirectoryEntries("*.dat")
            .filter { it.isRegularFile() }
            .sortedBy { it.name }
    }

    private fun openFile(index: Int) {
        closeCurrentReader()
        if (index < files.size) {
            currentReader = RandomAccessFile(files[index].toFile(), "r")
            logger.debug { "Opened file for reading: ${files[index]}" }
        }
    }

    private fun closeCurrentReader() {
        currentReader?.close()
        currentReader = null
    }

    public fun hasNext(): Boolean {
        if (nextEvent != null) return true

        while (currentFileIndex < files.size) {
            val reader = currentReader
            if (reader == null) {
                openFile(currentFileIndex)
                continue
            }

            try {
                if (reader.filePointer >= reader.length()) {
                    closeCurrentReader()
                    currentFileIndex++
                    if (currentFileIndex < files.size) {
                        openFile(currentFileIndex)
                    }
                    continue
                }

                val wallTs = reader.readLong()
                val eventTs = reader.readLong()
                val seq = reader.readLong()
                val eventType = reader.readUTF()
                val payloadSize = reader.readInt()
                val payload = ByteArray(payloadSize)
                reader.readFully(payload)

                nextEvent = RecordedEvent(
                    wallTimestamp = wallTs,
                    eventTimestamp = eventTs,
                    sequence = seq,
                    eventType = eventType,
                    payload = payload,
                )
                return true
            } catch (e: java.io.EOFException) {
                closeCurrentReader()
                currentFileIndex++
                if (currentFileIndex < files.size) {
                    openFile(currentFileIndex)
                }
            }
        }

        return false
    }

    public fun peek(): RecordedEvent? {
        if (nextEvent == null) {
            hasNext()
        }
        return nextEvent
    }

    public fun next(): RecordedEvent {
        if (nextEvent == null) {
            hasNext()
        }
        val event = nextEvent ?: throw NoSuchElementException("No more events")
        nextEvent = null
        return event
    }

    public fun close() {
        closeCurrentReader()
    }

    private companion object {
        private val logger = KotlinLogging.logger {}
    }
}
