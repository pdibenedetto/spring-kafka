/*
 * Copyright 2021-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.kafka.listener;

import java.util.Collection;
import java.util.List;

import org.apache.commons.logging.LogFactory;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;

import org.springframework.core.log.LogAccessor;
import org.springframework.kafka.support.KafkaUtils;
import org.springframework.kafka.support.TopicPartitionOffset;

/**
 * Listener container error handling contract.
 *
 * @author Gary Russell
 * @since 2.8
 *
 */
public interface CommonErrorHandler extends DeliveryAttemptAware {

	/**
	 * Return true if this error handler performs seeks on the failed record and remaining
	 * records (or just the remaining records after a failed record is recovered).
	 * @return true if the next poll should fetch records.
	 */
	default boolean seeksAfterHandling() {
		return false;
	}

	/**
	 * Return true if this error handler supports delivery attempts headers.
	 * @return true if capable.
	 */
	default boolean deliveryAttemptHeader() {
		return false;
	}

	/**
	 * Called when an exception is thrown with no records available, e.g. if the consumer
	 * poll throws an exception.
	 * @param thrownException the exception.
	 * @param consumer the consumer.
	 * @param container the container.
	 * @param batchListener true if the listener is a batch listener.
	 */
	default void handleOtherException(Exception thrownException, Consumer<?, ?> consumer,
			MessageListenerContainer container, boolean batchListener) {

		logger().error(thrownException, "'handleOtherException' is not implemented by this handler");
	}

	/**
	 * Handle the exception for a record listener when {@link #seeksAfterHandling()} returns
	 * false. Use this to handle just the single failed record.
	 * @param thrownException the exception.
	 * @param record the record.
	 * @param consumer the consumer.
	 * @param container the container.
	 * @return true if the error was "handled" or false if not and the container will
	 * re-submit the record to the listener.
	 * @since 2.9
	 * @see #seeksAfterHandling()
	 */
	default boolean handleOne(Exception thrownException, ConsumerRecord<?, ?> record, Consumer<?, ?> consumer,
			MessageListenerContainer container) {

		logger().error(thrownException, () -> "'handleOne' is not implemented by this handler for "
			+ KafkaUtils.format(record));
		return true;
	}

	/**
	 * Handle the exception for a record listener when {@link #seeksAfterHandling()} returns
	 * true. The failed record and all the remaining records from the poll are passed in.
	 * Usually used when the error handler performs seeks so that the remaining records
	 * will be redelivered on the next poll.
	 * @param thrownException the exception.
	 * @param records the remaining records including the one that failed.
	 * @param consumer the consumer.
	 * @param container the container.
	 * @see #seeksAfterHandling()
	 */
	default void handleRemaining(Exception thrownException, List<ConsumerRecord<?, ?>> records, Consumer<?, ?> consumer,
			MessageListenerContainer container) {

		logger().error(thrownException, "'handleRemaining' is not implemented by this handler");
	}

	/**
	 * Handle the exception for a batch listener. The complete {@link ConsumerRecords}
	 * from the poll is supplied. The error handler needs to perform seeks if you wish to
	 * reprocess the records in the batch.
	 * @param thrownException the exception.
	 * @param data the consumer records.
	 * @param consumer the consumer.
	 * @param container the container.
	 * @param invokeListener a callback to re-invoke the listener.
	 */
	default void handleBatch(Exception thrownException, ConsumerRecords<?, ?> data,
			Consumer<?, ?> consumer, MessageListenerContainer container, Runnable invokeListener) {

		logger().error(thrownException, "'handleBatch' is not implemented by this handler");
	}

	/**
	 * Common error handler logger.
	 * @return the logger.
	 * @since 3.1
	 */
	default LogAccessor logger() {
		return new LogAccessor(LogFactory.getLog(getClass()));
	}

	/**
	 * Handle the exception for a batch listener. The complete {@link ConsumerRecords}
	 * from the poll is supplied. Return the members of the batch that should be re-sent to
	 * the listener. The returned records MUST be in the same order as the original records.
	 * @param thrownException the exception.
	 * @param data the consumer records.
	 * @param consumer the consumer.
	 * @param container the container.
	 * @param invokeListener a callback to re-invoke the listener.
	 * @param <K> the key type.
	 * @param <V> the value type.
	 * @return the consumer records, or a subset.
	 * @since 2.9
	 */
	default <K, V> ConsumerRecords<K, V> handleBatchAndReturnRemaining(Exception thrownException,
			ConsumerRecords<?, ?> data, Consumer<?, ?> consumer, MessageListenerContainer container,
			Runnable invokeListener) {

		handleBatch(thrownException, data, consumer, container, invokeListener);
		return ConsumerRecords.empty();
	}

	@Override
	default int deliveryAttempt(TopicPartitionOffset topicPartitionOffset) {
		return 0;
	}

	/**
	 * Optional method to clear thread state; will be called just before a consumer
	 * thread terminates.
	 */
	default void clearThreadState() {
	}

	/**
	 * Return true if the offset should be committed for a handled error (no exception
	 * thrown).
	 * @return true to commit.
	 */
	default boolean isAckAfterHandle() {
		return true;
	}

	/**
	 * Set to false to prevent the container from committing the offset of a recovered
	 * record (when the error handler does not itself throw an exception).
	 * @param ack false to not commit.
	 */
	default void setAckAfterHandle(boolean ack) {
		throw new UnsupportedOperationException("This error handler does not support setting this property");
	}

	/**
	 * Called when partitions are assigned.
	 * @param consumer the consumer.
	 * @param partitions the newly assigned partitions.
	 * @param publishPause called to publish a consumer paused event.
	 * @since 2.8.9
	 */
	default void onPartitionsAssigned(Consumer<?, ?> consumer, Collection<TopicPartition> partitions,
			Runnable publishPause) {
	}

}
