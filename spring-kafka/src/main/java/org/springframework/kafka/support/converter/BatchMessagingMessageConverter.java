/*
 * Copyright 2016-present the original author or authors.
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

package org.springframework.kafka.support.converter;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.utils.Bytes;
import org.jspecify.annotations.Nullable;

import org.springframework.core.log.LogAccessor;
import org.springframework.core.log.LogMessage;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.DefaultKafkaHeaderMapper;
import org.springframework.kafka.support.JacksonPresent;
import org.springframework.kafka.support.KafkaHeaderMapper;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.KafkaNull;
import org.springframework.kafka.support.serializer.SerializationUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

/**
 * A Messaging {@link MessageConverter} implementation used with a batch
 * message listener; the consumer record values are extracted into a collection in
 * the message payload.
 * <p>
 * Populates {@link KafkaHeaders} based on the {@link ConsumerRecord} onto the returned message.
 * Each header is a collection where the position in the collection matches the payload
 * position.
 * <p>
 * If a {@link RecordMessageConverter} is provided, and the batch type is a {@link ParameterizedType}
 * with a single generic type parameter, each record will be passed to the converter, thus supporting
 * a method signature {@code List<MyType> myObjects}.
 *
 * @author Marius Bogoevici
 * @author Gary Russell
 * @author Dariusz Szablinski
 * @author Biju Kunjummen
 * @author Sanghyeok An
 * @author Hope Kim
 * @author Borahm Lee
 * @author Artem Bilan
 *
 * @since 1.1
 */
public class BatchMessagingMessageConverter implements BatchMessageConverter {

	protected final LogAccessor logger = new LogAccessor(getClass()); // NOSONAR

	@Nullable
	private final RecordMessageConverter recordConverter;

	private boolean generateMessageId = false;

	private boolean generateTimestamp = false;

	private @Nullable KafkaHeaderMapper headerMapper;

	private boolean rawRecordHeader;

	/**
	 * Create an instance that does not convert the record values.
	 */
	public BatchMessagingMessageConverter() {
		this(null);
	}

	/**
	 * Create an instance that converts record values using the supplied
	 * converter.
	 * @param recordConverter the converter.
	 * @since 1.3.2
	 */
	public BatchMessagingMessageConverter(@Nullable RecordMessageConverter recordConverter) {
		this.recordConverter = recordConverter;
		if (JacksonPresent.isJackson2Present()) {
			this.headerMapper = new DefaultKafkaHeaderMapper();
		}
	}

	/**
	 * Generate {@link Message} {@code ids} for produced messages. If set to {@code false},
	 * will try to use a default value. By default, set to {@code false}.
	 * @param generateMessageId true if a message id should be generated
	 */
	public void setGenerateMessageId(boolean generateMessageId) {
		this.generateMessageId = generateMessageId;
	}

	/**
	 * Generate {@code timestamp} for produced messages. If set to {@code false}, -1 is
	 * used instead. By default, set to {@code false}.
	 * @param generateTimestamp true if a timestamp should be generated
	 */
	public void setGenerateTimestamp(boolean generateTimestamp) {
		this.generateTimestamp = generateTimestamp;
	}

	/**
	 * Set the header mapper to map headers.
	 * @param headerMapper the mapper.
	 * @since 1.3
	 */
	public void setHeaderMapper(KafkaHeaderMapper headerMapper) {
		this.headerMapper = headerMapper;
	}

	@Nullable
	@Override
	public RecordMessageConverter getRecordMessageConverter() {
		return this.recordConverter;
	}

	/**
	 * Set to true to add the raw {@code List<ConsumerRecord<?, ?>>} as a header
	 * {@link KafkaHeaders#RAW_DATA}.
	 * @param rawRecordHeader true to add the header.
	 * @since 2.7
	 */
	public void setRawRecordHeader(boolean rawRecordHeader) {
		this.rawRecordHeader = rawRecordHeader;
	}

	@Override // NOSONAR
	public Message<?> toMessage(List<ConsumerRecord<?, ?>> records, @Nullable Acknowledgment acknowledgment,
			@Nullable Consumer<?, ?> consumer, Type type) {

		KafkaMessageHeaders kafkaMessageHeaders =
				new KafkaMessageHeaders(this.generateMessageId, this.generateTimestamp);

		Map<String, Object> rawHeaders = kafkaMessageHeaders.getRawHeaders();
		List<Object> payloads = new ArrayList<>();
		List<Object> keys = new ArrayList<>();
		List<String> topics = new ArrayList<>();
		List<Integer> partitions = new ArrayList<>();
		List<Long> offsets = new ArrayList<>();
		List<String> timestampTypes = new ArrayList<>();
		List<Long> timestamps = new ArrayList<>();
		List<Map<String, Object>> convertedHeaders = new ArrayList<>();
		List<Headers> natives = new ArrayList<>();
		List<ConsumerRecord<?, ?>> raws = new ArrayList<>();
		List<ConversionException> conversionFailures = new ArrayList<>();

		addToRawHeaders(rawHeaders, convertedHeaders, natives, raws, conversionFailures);
		commonHeaders(acknowledgment, consumer, rawHeaders, keys, topics, partitions, offsets, timestampTypes,
				timestamps);

		String listenerInfo = null;
		for (ConsumerRecord<?, ?> record : records) {
			addRecordInfo(record, type, payloads, keys, topics, partitions, offsets, timestampTypes, timestamps,
					conversionFailures);
			Headers recordHeaders = record.headers();
			if (this.headerMapper != null && recordHeaders != null) {
				Map<String, Object> converted = convertHeaders(recordHeaders, convertedHeaders);
				Object obj = converted.get(KafkaHeaders.LISTENER_INFO);
				if (obj instanceof String info) {
					listenerInfo = info;
				}
			}
			else {
				natives.add(recordHeaders);
			}
			if (this.rawRecordHeader) {
				raws.add(record);
			}
		}
		if (this.headerMapper == null && !natives.isEmpty()) {
			this.logger.debug(() ->
					"No header mapper is available; Jackson is required for the default mapper; "
							+ "headers (if present) are not mapped but provided raw in "
							+ KafkaHeaders.NATIVE_HEADERS);
		}
		if (listenerInfo != null) {
			rawHeaders.put(KafkaHeaders.LISTENER_INFO, listenerInfo);
		}
		return MessageBuilder.createMessage(payloads, kafkaMessageHeaders);
	}

	private void addToRawHeaders(Map<String, Object> rawHeaders, List<Map<String, Object>> convertedHeaders,
			List<Headers> natives, List<ConsumerRecord<?, ?>> raws, List<ConversionException> conversionFailures) {

		if (this.headerMapper != null) {
			rawHeaders.put(KafkaHeaders.BATCH_CONVERTED_HEADERS, convertedHeaders);
		}
		else {
			rawHeaders.put(KafkaHeaders.NATIVE_HEADERS, natives);
		}
		if (this.rawRecordHeader) {
			rawHeaders.put(KafkaHeaders.RAW_DATA, raws);
		}
		rawHeaders.put(KafkaHeaders.CONVERSION_FAILURES, conversionFailures);
	}

	private void addRecordInfo(ConsumerRecord<?, ?> record, Type type, List<Object> payloads, List<Object> keys,
			List<String> topics, List<Integer> partitions, List<Long> offsets, List<String> timestampTypes,
			List<Long> timestamps, List<ConversionException> conversionFailures) {

		payloads.add(obtainPayload(type, record, conversionFailures));
		keys.add(record.key());
		topics.add(record.topic());
		partitions.add(record.partition());
		offsets.add(record.offset());
		timestamps.add(record.timestamp());
		TimestampType timestampType = record.timestampType();
		if (timestampType != null) {
			timestampTypes.add(timestampType.name());
		}
	}

	private @Nullable Object obtainPayload(Type type, ConsumerRecord<?, ?> record, List<ConversionException> conversionFailures) {
		return this.recordConverter == null || !containerType(type)
				? extractAndConvertValue(record, type)
				: convert(record, type, conversionFailures);
	}

	private Map<String, Object> convertHeaders(Headers headers, List<Map<String, Object>> convertedHeaders) {
		Map<String, Object> converted = new HashMap<>();
		if (this.headerMapper != null) {
			this.headerMapper.toHeaders(headers, converted);
		}
		convertedHeaders.add(converted);
		return converted;
	}

	@Override
	public List<ProducerRecord<?, ?>> fromMessage(Message<?> message, String defaultTopic) {
		throw new UnsupportedOperationException();
	}

	/**
	 * Subclasses can convert the value; by default, it's returned as provided by Kafka
	 * unless a {@link RecordMessageConverter} has been provided.
	 * @param record the record.
	 * @param type the required type.
	 * @return the value.
	 */
	protected Object extractAndConvertValue(ConsumerRecord<?, ?> record, Type type) {
		return record.value() == null ? KafkaNull.INSTANCE : record.value();
	}

	/**
	 * Convert the record value.
	 * @param record the record.
	 * @param type the type - must be a {@link ParameterizedType} with a single generic
	 * type parameter.
	 * @param conversionFailures Conversion failures.
	 * @return the converted payload.
	 */
	protected @Nullable Object convert(ConsumerRecord<?, ?> record, Type type, List<ConversionException> conversionFailures) {
		try {
			if (this.recordConverter != null) {
				Object payload = this.recordConverter
						.toMessage(record, null, null, ((ParameterizedType) type).getActualTypeArguments()[0]).getPayload();
				conversionFailures.add(null);
				return payload;
			}
			else {
				return null;
			}
		}
		catch (ConversionException ex) {
			byte[] original = null;
			if (record.value() instanceof byte[] bytes) {
				original = bytes;
			}
			else if (record.value() instanceof Bytes bytes) {
				original = bytes.get();
			}
			else if (record.value() instanceof String string) {
				original = string.getBytes(StandardCharsets.UTF_8);
			}
			if (original != null) {
				SerializationUtils.deserializationException(record.headers(), original, ex, false);
				conversionFailures.add(ex);
				this.logger.warn(ex,
						LogMessage.format("Could not convert message for topic=%s, partition=%d, offset=%d",
								record.topic(),
								record.partition(),
								record.offset()));
				return null;
			}
			throw new ConversionException("The batch converter can only report conversion failures to the listener "
					+ "if the record.value() is byte[], Bytes, or String", ex);
		}
	}

	/**
	 * Return true if the type is a parameterized type with a single generic type
	 * parameter.
	 * @param type the type.
	 * @return true if the conditions are met.
	 */
	private boolean containerType(Type type) {
		return type instanceof ParameterizedType parameterizedType
				&& parameterizedType.getActualTypeArguments().length == 1;
	}

}
