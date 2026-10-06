/*
 * MIT License
 *
 * Copyright (c) 2025 Jonathan Glanz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.tfv.deskflow.client.io

import org.tfv.deskflow.client.util.logging.KLoggingManager
import org.tfv.deskflow.client.io.MessageTemplate.Companion.templateFromPrefix
import org.tfv.deskflow.client.io.msgs.Message
import kotlin.reflect.full.createInstance

import java.io.ByteArrayInputStream
import java.io.DataInputStream

class MessageParser() {

    private var pendingMessageSize: Int = 0

    /**
     * Parse the message from the buffer
     *
     * @param buffer The buffer to parse
     * @return The list of parsed messages
     * @throws MessageParserCorruptionException if the buffer contains corrupted data
     *   that cannot be recovered from (e.g., garbage length prefix, parser deadlock).
     *   The caller should disconnect and reconnect.
     */
    fun parseBuffer(buffer: DynamicByteBuffer): List<Message> {
        log.debug { "parse buffer size: ${buffer.size()}" }
        val inputStream = buffer.dataInputStream
        val msgList = mutableListOf<Message>()
        var totalBytesConsumed = 0

        while (true) {
            var availableSize = buffer.availableReadSize
            if (pendingMessageSize == 0) {
                if (availableSize < Int.SIZE_BYTES) {
                    break
                }

                pendingMessageSize = inputStream.readInt()
                totalBytesConsumed += Int.SIZE_BYTES
                availableSize -= Int.SIZE_BYTES

                // Guard: reject obviously corrupted length prefixes.
                // Deskflow messages are small (typically < 64 KB; clipboard
                // data is the largest at a few MB). A value above 16 MB is
                // almost certainly a corruption artifact and would cause the
                // parser to stall forever waiting for data that never arrives.
                if (pendingMessageSize > MAX_MESSAGE_SIZE) {
                    val err = MessageParserCorruptionException(
                        "Corrupted message length: $pendingMessageSize > $MAX_MESSAGE_SIZE"
                    )
                    reset()
                    throw err
                }
            }

            if (availableSize < pendingMessageSize) {
                break
            }

            val messageData = buffer.pop(pendingMessageSize)
            totalBytesConsumed += pendingMessageSize
            pendingMessageSize = 0

            val message = parseMessage(messageData)
            if (message == null) {
                log.warn { "Error parsing message of size ${messageData.size}" }
                continue
            }
            msgList.add(message)
        }

        // Stale buffer detection: if we scanned a large amount of data without
        // producing any complete messages, the parser is likely desynchronized
        // (e.g., a garbage length prefix caused us to skip past valid data).
        if (msgList.isEmpty() && totalBytesConsumed > MAX_BUFFER_SCAN_BYTES) {
            val err = MessageParserCorruptionException(
                "Parser desynchronized: scanned $totalBytesConsumed bytes without parsing a message"
            )
            reset()
            throw err
        }

        return msgList
    }

    /**
     * Resets the parser state, discarding any partially-read message size.
     * Call after a corruption event to avoid carrying stale state into the
     * next parse cycle.
     */
    fun reset() {
        pendingMessageSize = 0
    }

    fun parseMessage(data: ByteArray): Message? {
        try {
            require(data.size >= 4) { "Message data must be at least 4 bytes" }
            val prefix = String(data, 0, 4)
            val template = templateFromPrefix(prefix)
            if (template == null) {
                log.error { "Template not found for prefix: $prefix" }
                return null
            }
            log.debug { "MessageTemplate: $template" }
            require(template.clazz != null) {
                "Message class is null for template: $template"
            }

            try {


                val message = template.clazz.createInstance() as Message
                // TODO: Read from the macro DynamicByteBuffer instead, but good enough for now
                val dataOffset = template.code.length
                val dataSize = data.size - dataOffset
                message.header.dataSize = dataSize
                message.readData(DataInputStream(ByteArrayInputStream(data, dataOffset, dataSize)), dataSize)

                return message
            } catch (err: Exception) {
                log.error(err) { "Error creating message instance  (type=${template.code}): ${err.message}" }
                throw err
            }
        } catch (err: Exception) {
            log.error(err) { "Unable to parse message: ${err.message}" }
            return null
        }
    }


    companion object {

        /**
         * Maximum allowed message size in bytes. Deskflow messages are small:
         * mouse events are 5-8 bytes, keyboard events are a few dozen bytes,
         * clipboard data is the largest at a few MB. 16 MB provides ample
         * headroom while catching garbage length prefixes from TCP corruption.
         */
        private const val MAX_MESSAGE_SIZE = 16 * 1024 * 1024

        /**
         * Maximum bytes to scan in a single parseBuffer() call without
         * producing any complete messages before declaring the parser
         * desynchronized. This prevents infinite stalling when the parser
         * is stuck on a garbage length prefix.
         */
        private const val MAX_BUFFER_SCAN_BYTES = 64 * 1024

        	private val log = KLoggingManager.logger(MessageParser::class.java.simpleName)

    }
}