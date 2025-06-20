/*
 * Copyright 2017-present the original author or authors.
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.ProducerFencedException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.core.ProducerFactoryUtils;
import org.springframework.kafka.event.ConsumerStoppedEvent;
import org.springframework.kafka.event.ConsumerStoppedEvent.Reason;
import org.springframework.kafka.event.ListenerContainerIdleEvent;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.ContainerProperties.AssignmentCommitOption;
import org.springframework.kafka.listener.ContainerProperties.EOSMode;
import org.springframework.kafka.support.DefaultKafkaHeaderMapper;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.condition.EmbeddedKafkaCondition;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.kafka.transaction.KafkaTransactionManager;
import org.springframework.messaging.MessageHeaders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.util.backoff.FixedBackOff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willReturn;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * @author Gary Russell
 * @author Artem Bilan
 * @author Wang Zhiyang
 * @author Soby Chacko
 * @author Raphael Rösch
 *
 * @since 1.3
 *
 */
@EmbeddedKafka(topics = { TransactionalContainerTests.topic1, TransactionalContainerTests.topic2,
		TransactionalContainerTests.topic3, TransactionalContainerTests.topic3DLT, TransactionalContainerTests.topic4,
		TransactionalContainerTests.topic5, TransactionalContainerTests.topic6, TransactionalContainerTests.topic7,
		TransactionalContainerTests.topic8, TransactionalContainerTests.topic8DLT, TransactionalContainerTests.topic9,
		TransactionalContainerTests.topic10},
		brokerProperties = { "transaction.state.log.replication.factor=1", "transaction.state.log.min.isr=1" })
public class TransactionalContainerTests {

	public static final String topic1 = "txTopic1";

	public static final String topic2 = "txTopic2";

	public static final String topic3 = "txTopic3";

	public static final String topic3DLT = "txTopic3-dlt";

	public static final String topic4 = "txTopic4";

	public static final String topic5 = "txTopic5";

	public static final String topic6 = "txTopic6";

	public static final String topic7 = "txTopic7";

	public static final String topic8 = "txTopic8";

	public static final String topic8DLT = "txTopic8-dlt";

	public static final String topic9 = "txTopic9";

	public static final String topic10 = "txTopic10";

	private static EmbeddedKafkaBroker embeddedKafka;

	@BeforeAll
	public static void setup() {
		embeddedKafka = EmbeddedKafkaCondition.getBroker();
	}

	@Test
	public void testConsumeAndProduceTransactionKTM() throws Exception {
		testConsumeAndProduceTransactionGuts(false, AckMode.RECORD, EOSMode.V2);
	}

	@Test
	public void testConsumeAndProduceTransactionHandleError() throws Exception {
		testConsumeAndProduceTransactionGuts(true, AckMode.RECORD, EOSMode.V2);
	}

	@Test
	public void testConsumeAndProduceTransactionKTMManual() throws Exception {
		testConsumeAndProduceTransactionGuts(false, AckMode.MANUAL_IMMEDIATE, EOSMode.V2);
	}

	@Test
	public void testConsumeAndProduceTransactionStopWhenFenced() throws Exception {
		testConsumeAndProduceTransactionGuts(false, AckMode.RECORD, EOSMode.V2, true);
	}

	private void testConsumeAndProduceTransactionGuts(boolean handleError, AckMode ackMode,
			EOSMode eosMode) throws Exception {

		testConsumeAndProduceTransactionGuts(handleError, ackMode, eosMode, false);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void testConsumeAndProduceTransactionGuts(boolean handleError, AckMode ackMode,
			EOSMode eosMode, boolean stopWhenFenced) throws Exception {

		Consumer consumer = mock(Consumer.class);
		AtomicBoolean assigned = new AtomicBoolean();
		final TopicPartition topicPartition = new TopicPartition("foo", 0);
		willAnswer(i -> {
			((ConsumerRebalanceListener) i.getArgument(1))
					.onPartitionsAssigned(Collections.singletonList(topicPartition));
			assigned.set(true);
			return null;
		}).given(consumer).subscribe(any(Collection.class), any(ConsumerRebalanceListener.class));
		ConsumerRecords records = new ConsumerRecords(Collections.singletonMap(topicPartition,
				Collections.singletonList(new ConsumerRecord<>("foo", 0, 0, "key", "value"))), Map.of());
		ConsumerRecords empty = new ConsumerRecords(Collections.emptyMap(), Map.of());
		final AtomicBoolean done = new AtomicBoolean();
		willAnswer(i -> {
			if (done.compareAndSet(false, true)) {
				return records;
			}
			else {
				Thread.sleep(500);
				return empty;
			}
		}).given(consumer).poll(any(Duration.class));
		ConsumerFactory cf = mock(ConsumerFactory.class);
		willReturn(consumer).given(cf).createConsumer("group", "", null, KafkaTestUtils.defaultPropertyOverrides());
		Producer producer = mock(Producer.class);
		if (stopWhenFenced) {
			willAnswer(inv -> {
				if (assigned.get()) {
					throw new ProducerFencedException("fenced");
				}
				return null;
			}).given(producer).sendOffsetsToTransaction(any(), any(ConsumerGroupMetadata.class));
		}
		given(producer.send(any(), any())).willReturn(new CompletableFuture<>());
		final CountDownLatch closeLatch = new CountDownLatch(2);
		willAnswer(i -> {
			closeLatch.countDown();
			return null;
		}).given(producer).close(any());
		ProducerFactory pf = mock(ProducerFactory.class);
		given(pf.transactionCapable()).willReturn(true);
		willReturn(producer).given(pf).createProducer(isNull());
		KafkaTransactionManager tm = new KafkaTransactionManager(pf);
		ContainerProperties props = new ContainerProperties("foo");
		props.setAckMode(ackMode);
		props.setGroupId("group");
		props.setKafkaAwareTransactionManager(tm);
		props.setAssignmentCommitOption(AssignmentCommitOption.ALWAYS);
		props.setEosMode(eosMode);
		props.setStopContainerWhenFenced(stopWhenFenced);
		ConsumerGroupMetadata consumerGroupMetadata = new ConsumerGroupMetadata("group");
		given(consumer.groupMetadata()).willReturn(consumerGroupMetadata);
		final KafkaTemplate template = new KafkaTemplate(pf);
		if (AckMode.MANUAL_IMMEDIATE.equals(ackMode)) {
			props.setMessageListener((AcknowledgingMessageListener<Object, Object>) (data, acknowledgment) -> {
				template.send("bar", "baz");
				if (handleError) {
					throw new RuntimeException("fail");
				}
				acknowledgment.acknowledge();
			});
		}
		else {
			props.setMessageListener((MessageListener) m -> {
				template.send("bar", "baz");
				if (handleError) {
					throw new RuntimeException("fail");
				}
			});
		}
		KafkaMessageListenerContainer container = new KafkaMessageListenerContainer<>(cf, props);
		container.setBeanName("commit");
		if (handleError) {
			container.setCommonErrorHandler(new CommonErrorHandler() {
			});
		}
		CountDownLatch stopEventLatch = new CountDownLatch(1);
		AtomicReference<ConsumerStoppedEvent> stopEvent = new AtomicReference<>();
		container.setApplicationEventPublisher(event -> {
			if (event instanceof ConsumerStoppedEvent) {
				stopEvent.set((ConsumerStoppedEvent) event);
				stopEventLatch.countDown();
			}
		});
		container.start();
		assertThat(closeLatch.await(10, TimeUnit.SECONDS)).isTrue();
		InOrder inOrder = inOrder(producer);
		inOrder.verify(producer).beginTransaction();
		inOrder.verify(producer).sendOffsetsToTransaction(Collections.singletonMap(topicPartition,
				new OffsetAndMetadata(0)), consumerGroupMetadata);
		if (stopWhenFenced) {
			assertThat(stopEventLatch.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(stopEvent.get().getReason()).isEqualTo(Reason.FENCED);
		}
		else {
			inOrder.verify(producer).commitTransaction();
			inOrder.verify(producer).close(any());
			inOrder.verify(producer).beginTransaction();
			ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
			inOrder.verify(producer).send(captor.capture(), any(Callback.class));
			assertThat(captor.getValue()).isEqualTo(new ProducerRecord("bar", "baz"));
			inOrder.verify(producer).sendOffsetsToTransaction(Collections.singletonMap(topicPartition,
					new OffsetAndMetadata(1)), consumerGroupMetadata);
			inOrder.verify(producer).commitTransaction();
			inOrder.verify(producer).close(any());
			container.stop();
			verify(pf, times(2)).createProducer(isNull());
			verifyNoMoreInteractions(producer);
			assertThat(stopEventLatch.await(10, TimeUnit.SECONDS)).isTrue();
			assertThat(stopEvent.get().getReason()).isEqualTo(Reason.NORMAL);
		}
		assertThat(stopEvent.get().getSource()).isSameAs(container);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testConsumeAndProduceTransactionRollback() throws Exception {
		Consumer consumer = mock(Consumer.class);
		final TopicPartition topicPartition0 = new TopicPartition("foo", 0);
		final TopicPartition topicPartition1 = new TopicPartition("foo", 1);
		Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = new HashMap<>();
		recordMap.put(topicPartition0, Collections.singletonList(new ConsumerRecord<>("foo", 0, 0, "key", "value")));
		recordMap.put(topicPartition1, Collections.singletonList(new ConsumerRecord<>("foo", 1, 0, "key", "value")));
		ConsumerRecords records = new ConsumerRecords(recordMap, Map.of());
		final AtomicBoolean done = new AtomicBoolean();
		willAnswer(i -> {
			if (done.compareAndSet(false, true)) {
				return records;
			}
			else {
				Thread.sleep(500);
				return null;
			}
		}).given(consumer).poll(any(Duration.class));
		final CountDownLatch seekLatch = new CountDownLatch(2);
		willAnswer(i -> {
			seekLatch.countDown();
			return null;
		}).given(consumer).seek(any(), anyLong());
		ConsumerFactory cf = mock(ConsumerFactory.class);
		willReturn(consumer).given(cf).createConsumer("group", "", null, KafkaTestUtils.defaultPropertyOverrides());
		Producer producer = mock(Producer.class);
		final CountDownLatch closeLatch = new CountDownLatch(1);
		willAnswer(i -> {
			closeLatch.countDown();
			return null;
		}).given(producer).close(any());
		ProducerFactory pf = mock(ProducerFactory.class);
		given(pf.transactionCapable()).willReturn(true);
		given(pf.createProducer(isNull())).willReturn(producer);
		KafkaTransactionManager tm = new KafkaTransactionManager(pf);
		ContainerProperties props = new ContainerProperties(new TopicPartitionOffset("foo", 0),
				new TopicPartitionOffset("foo", 1));
		props.setGroupId("group");
		props.setKafkaAwareTransactionManager(tm);
		props.setDeliveryAttemptHeader(true);
		final KafkaTemplate template = new KafkaTemplate(pf);
		AtomicReference<Header> delivery = new AtomicReference();
		props.setMessageListener((MessageListener<?, ?>) m -> {
			delivery.set(m.headers().lastHeader(KafkaHeaders.DELIVERY_ATTEMPT));
			template.send("bar", "baz");
			throw new RuntimeException("fail");
		});
		KafkaMessageListenerContainer container = new KafkaMessageListenerContainer<>(cf, props);
		container.setBeanName("rollback");
		container.start();
		assertThat(closeLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(seekLatch.await(10, TimeUnit.SECONDS)).isTrue();
		InOrder inOrder = inOrder(producer);
		inOrder.verify(producer).beginTransaction();
		ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
		verify(producer).send(captor.capture(), any(Callback.class));
		assertThat(captor.getValue()).isEqualTo(new ProducerRecord("bar", "baz"));
		inOrder.verify(producer, never()).sendOffsetsToTransaction(anyMap(), any(ConsumerGroupMetadata.class));
		inOrder.verify(producer, never()).commitTransaction();
		inOrder.verify(producer).abortTransaction();
		inOrder.verify(producer).close(any());
		verify(consumer).seek(topicPartition0, 0);
		verify(consumer).seek(topicPartition1, 0);
		verify(consumer, never()).commitSync(anyMap(), any());
		container.stop();
		verify(pf, times(1)).createProducer(isNull());
		assertThat(delivery.get()).isNotNull();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testConsumeAndProduceTransactionRollbackBatch() throws Exception {
		Consumer consumer = mock(Consumer.class);
		final TopicPartition topicPartition0 = new TopicPartition("foo", 0);
		final TopicPartition topicPartition1 = new TopicPartition("foo", 1);
		Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = new HashMap<>();
		recordMap.put(topicPartition0, Collections.singletonList(new ConsumerRecord<>("foo", 0, 0, "key", "value")));
		recordMap.put(topicPartition1, Collections.singletonList(new ConsumerRecord<>("foo", 1, 0, "key", "value")));
		ConsumerRecords records = new ConsumerRecords(recordMap, Map.of());
		final AtomicBoolean done = new AtomicBoolean();
		willAnswer(i -> {
			if (done.compareAndSet(false, true)) {
				return records;
			}
			else {
				Thread.sleep(500);
				return null;
			}
		}).given(consumer).poll(any(Duration.class));
		final CountDownLatch seekLatch = new CountDownLatch(2);
		willAnswer(i -> {
			seekLatch.countDown();
			return null;
		}).given(consumer).seek(any(), anyLong());
		ConsumerFactory cf = mock(ConsumerFactory.class);
		willReturn(consumer).given(cf).createConsumer("group", "", null, KafkaTestUtils.defaultPropertyOverrides());
		Producer producer = mock(Producer.class);
		final CountDownLatch closeLatch = new CountDownLatch(1);
		willAnswer(i -> {
			closeLatch.countDown();
			return null;
		}).given(producer).close(any());
		ProducerFactory pf = mock(ProducerFactory.class);
		given(pf.transactionCapable()).willReturn(true);
		given(pf.createProducer(isNull())).willReturn(producer);
		KafkaTransactionManager tm = new KafkaTransactionManager(pf);
		ContainerProperties props = new ContainerProperties(new TopicPartitionOffset("foo", 0),
				new TopicPartitionOffset("foo", 1));
		props.setGroupId("group");
		props.setKafkaAwareTransactionManager(tm);
		props.setSubBatchPerPartition(false);
		final KafkaTemplate template = new KafkaTemplate(pf);
		props.setMessageListener((BatchMessageListener) recordlist -> {
			template.send("bar", "baz");
			throw new RuntimeException("fail");
		});
		KafkaMessageListenerContainer container = new KafkaMessageListenerContainer<>(cf, props);
		container.setBeanName("rollback");
		container.start();
		assertThat(closeLatch.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(seekLatch.await(10, TimeUnit.SECONDS)).isTrue();
		InOrder inOrder = inOrder(producer);
		inOrder.verify(producer).beginTransaction();
		ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
		verify(producer).send(captor.capture(), any(Callback.class));
		assertThat(captor.getValue()).isEqualTo(new ProducerRecord("bar", "baz"));
		inOrder.verify(producer, never()).sendOffsetsToTransaction(anyMap(), any(ConsumerGroupMetadata.class));
		inOrder.verify(producer, never()).commitTransaction();
		inOrder.verify(producer).abortTransaction();
		inOrder.verify(producer).close(any());
		verify(consumer).seek(topicPartition0, 0);
		verify(consumer).seek(topicPartition1, 0);
		verify(consumer, never()).commitSync(anyMap(), any());
		container.stop();
		verify(pf, times(1)).createProducer(isNull());
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	public void testConsumeAndProduceTransactionExternalTM() throws Exception {
		Consumer consumer = mock(Consumer.class);
		final TopicPartition topicPartition = new TopicPartition("foo", 0);
		final ConsumerRecords records = new ConsumerRecords(Collections.singletonMap(topicPartition,
				Collections.singletonList(new ConsumerRecord<>("foo", 0, 0, "key", "value"))), Map.of());
		final AtomicBoolean done = new AtomicBoolean();
		willAnswer(i -> {
			if (done.compareAndSet(false, true)) {
				return records;
			}
			else {
				Thread.sleep(500);
				return null;
			}
		}).given(consumer).poll(any(Duration.class));
		ConsumerFactory cf = mock(ConsumerFactory.class);
		willReturn(consumer).given(cf).createConsumer("group", "", null, KafkaTestUtils.defaultPropertyOverrides());
		Producer producer = mock(Producer.class);
		given(producer.send(any(), any())).willReturn(new CompletableFuture<>());

		final CountDownLatch closeLatch = new CountDownLatch(1);

		willAnswer(i -> {
			closeLatch.countDown();
			return null;
		}).given(producer).close(any());

		final ProducerFactory pf = mock(ProducerFactory.class);
		given(pf.transactionCapable()).willReturn(true);
		given(pf.createProducer(isNull())).willReturn(producer);
		ContainerProperties props = new ContainerProperties(new TopicPartitionOffset("foo", 0));
		props.setGroupId("group");
		props.setKafkaAwareTransactionManager(new KafkaTransactionManager<>(pf));
		final KafkaTemplate template = new KafkaTemplate(pf);
		ConsumerGroupMetadata meta = mock(ConsumerGroupMetadata.class);
		props.setMessageListener((MessageListener<String, String>) m -> {
			template.send("bar", "baz");
			template.sendOffsetsToTransaction(Collections.singletonMap(new TopicPartition(m.topic(), m.partition()),
					new OffsetAndMetadata(m.offset() + 1)), meta);
		});
		KafkaMessageListenerContainer container = new KafkaMessageListenerContainer<>(cf, props);
		container.setBeanName("commit");
		container.start();

		assertThat(closeLatch.await(10, TimeUnit.SECONDS)).isTrue();

		InOrder inOrder = inOrder(producer);
		inOrder.verify(producer).beginTransaction();
		ArgumentCaptor<ProducerRecord> captor = ArgumentCaptor.forClass(ProducerRecord.class);
		inOrder.verify(producer).send(captor.capture(), any(Callback.class));
		assertThat(captor.getValue()).isEqualTo(new ProducerRecord("bar", "baz"));
		inOrder.verify(producer).sendOffsetsToTransaction(Collections.singletonMap(topicPartition,
				new OffsetAndMetadata(1)), meta);
		inOrder.verify(producer).commitTransaction();
		inOrder.verify(producer).close(any());
		container.stop();
		verify(pf).createProducer(isNull());
	}

	@SuppressWarnings({ "unchecked"})
	@Test
	public void testRollbackRecord() throws Exception {
		Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, "txTest1", false);
		props.put(ConsumerConfig.GROUP_ID_CONFIG, "group");
		props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
		DefaultKafkaConsumerFactory<Integer, String> cf = new DefaultKafkaConsumerFactory<>(props);
		ContainerProperties containerProps = new ContainerProperties(topic1, topic2);
		containerProps.setGroupId("group");
		containerProps.setPollTimeout(500L);
		containerProps.setIdleEventInterval(500L);
		containerProps.setFixTxOffsets(true);

		Map<String, Object> senderProps = KafkaTestUtils.producerProps(embeddedKafka);
		DefaultKafkaProducerFactory<Integer, String> pf = new DefaultKafkaProducerFactory<>(senderProps);
		pf.setTransactionIdPrefix("rr.");

		final KafkaTemplate<Integer, String> template = new KafkaTemplate<>(pf);
		final AtomicBoolean failed = new AtomicBoolean();
		final CountDownLatch latch = new CountDownLatch(3);
		final AtomicReference<String> transactionalId = new AtomicReference<>();
		containerProps.setMessageListener((MessageListener<Integer, String>) message -> {
			latch.countDown();
			if (failed.compareAndSet(false, true)) {
				throw new RuntimeException("fail");
			}
			/*
			 * Send a message to topic2 and wait for it so we don't stop the container too soon.
			 */
			if (message.topic().equals(topic1)) {
				template.send(topic2, "bar");
				template.flush();
				transactionalId.set(KafkaTestUtils.getPropertyValue(
						ProducerFactoryUtils.getTransactionalResourceHolder(pf).getProducer(),
						"delegate.transactionManager.transactionalId", String.class));
			}
		});

		@SuppressWarnings({ "rawtypes" })
		KafkaTransactionManager tm = new KafkaTransactionManager(pf);
		containerProps.setKafkaAwareTransactionManager(tm);
		KafkaMessageListenerContainer<Integer, String> container =
				new KafkaMessageListenerContainer<>(cf, containerProps);
		container.setBeanName("testRollbackRecord");
		AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
		CountDownLatch idleLatch = new CountDownLatch(1);
		container.setApplicationEventPublisher(event -> {
			if (event instanceof ListenerContainerIdleEvent) {
				Consumer<?, ?> consumer = ((ListenerContainerIdleEvent) event).getConsumer();
				committed.set(consumer.committed(Set.of(new TopicPartition(topic1, 0), new TopicPartition(topic1, 1))));
				if (committed.get().get(new TopicPartition(topic1, 0)) != null) {
					idleLatch.countDown();
				}
			}
		});
		container.start();

		template.setDefaultTopic(topic1);
		template.executeInTransaction(t -> {
			template.sendDefault(0, 0, "foo");
			return null;
		});
		assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
		assertThat(idleLatch.await(10, TimeUnit.SECONDS)).isTrue();
		TopicPartition partition0 = new TopicPartition(topic1, 0);
		assertThat(committed.get().get(partition0).offset()).isEqualTo(2L);
		assertThat(committed.get().get(new TopicPartition(topic1, 1))).isNull();
		container.stop();
		Consumer<Integer, String> consumer = cf.createConsumer();
		final CountDownLatch subsLatch = new CountDownLatch(1);
		consumer.subscribe(List.of(topic1), new ConsumerRebalanceListener() {

			@Override
			public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
				// empty
			}

			@Override
			public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
				subsLatch.countDown();
			}

		});
		ConsumerRecords<Integer, String> records = null;
		int n = 0;
		while (subsLatch.getCount() > 0 && n++ < 600) {
			records = consumer.poll(Duration.ofMillis(100));
		}
		assertThat(subsLatch.await(1, TimeUnit.MILLISECONDS)).isTrue();
		assertThat(records.count()).isEqualTo(0);
		assertThat(consumer.position(partition0)).isEqualTo(2L);
		assertThat(transactionalId.get()).startsWith("rr.");
		pf.destroy();
		consumer.close();
	}

	@Test
	public void testFixLag() throws InterruptedException {
		testFixLagGuts(topic5, 0);
	}

	@Test
	public void testFixLagKTM() throws InterruptedException {
		testFixLagGuts(topic6, 1);
	}

	@SuppressWarnings({"unchecked"})
	private void testFixLagGuts(String topic, int whichTm) throws InterruptedException {
		Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, "txTest2", false);
		props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
		DefaultKafkaConsumerFactory<Integer, String> cf = new DefaultKafkaConsumerFactory<>(props);
		ContainerProperties containerProps = new ContainerProperties(topic);
		containerProps.setGroupId("txTest2");
		containerProps.setPollTimeout(500L);
		containerProps.setIdleEventInterval(500L);
		containerProps.setFixTxOffsets(true);
		Map<String, Object> senderProps = KafkaTestUtils.producerProps(embeddedKafka);
		DefaultKafkaProducerFactory<Integer, String> pf = new DefaultKafkaProducerFactory<>(senderProps);
		pf.setTransactionIdPrefix("fl.");
		switch (whichTm) {
		case 0:
			break;
		case 1:
			containerProps.setKafkaAwareTransactionManager(new KafkaTransactionManager<>(pf));
			break;
		}

		final KafkaTemplate<Integer, String> template = new KafkaTemplate<>(pf);
		final CountDownLatch latch = new CountDownLatch(1);
		containerProps.setMessageListener((MessageListener<Integer, String>) message -> {
		});

		KafkaMessageListenerContainer<Integer, String> container =
				new KafkaMessageListenerContainer<>(cf, containerProps);
		container.setBeanName("testRollbackRecord");
		AtomicReference<Map<TopicPartition, OffsetAndMetadata>> committed = new AtomicReference<>();
		container.setApplicationEventPublisher(event -> {
			if (event instanceof ListenerContainerIdleEvent) {
				Consumer<?, ?> consumer = ((ListenerContainerIdleEvent) event).getConsumer();
				committed.set(consumer.committed(Set.of(new TopicPartition(topic, 0))));
				if (committed.get().get(new TopicPartition(topic, 0)) != null) {
					latch.countDown();
				}
			}
		});
		container.start();

		template.setDefaultTopic(topic);
		template.executeInTransaction(t -> {
			template.sendDefault(0, 0, "foo");
			return null;
		});
		assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
		TopicPartition partition0 = new TopicPartition(topic, 0);
		assertThat(committed.get().get(partition0).offset()).isEqualTo(2L);
		assertThat(KafkaTestUtils.getPropertyValue(container, "listenerConsumer.lastCommits", Map.class)).isEmpty();
		container.stop();
		pf.destroy();
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testMaxFailures() throws Exception {
		String group = "groupInARBP";
		Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, group, false);
		props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
		DefaultKafkaConsumerFactory<Integer, String> cf = new DefaultKafkaConsumerFactory<>(props);
		ContainerProperties containerProps = new ContainerProperties(topic3);
		containerProps.setPollTimeout(10_000);

		Map<String, Object> senderProps = KafkaTestUtils.producerProps(embeddedKafka);
		DefaultKafkaProducerFactory<Object, Object> pf = new DefaultKafkaProducerFactory<>(senderProps);
		pf.setTransactionIdPrefix("maxAtt.");
		final KafkaTemplate<Object, Object> template = new KafkaTemplate<>(pf);
		final CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> data = new AtomicReference<>();
		containerProps.setMessageListener((MessageListener<Integer, String>) message -> {
			data.set(message.value());
			if (message.offset() == 0) {
				throw new RuntimeException("fail for max failures");
			}
			latch.countDown();
		});

		@SuppressWarnings({ "rawtypes" })
		KafkaTransactionManager tm = new KafkaTransactionManager(pf);
		containerProps.setKafkaAwareTransactionManager(tm);
		KafkaMessageListenerContainer<Integer, String> container =
				new KafkaMessageListenerContainer<>(cf, containerProps);
		container.setBeanName("testMaxFailures");
		final CountDownLatch recoverLatch = new CountDownLatch(1);
		final KafkaOperations<Object, Object> dlTemplate = spy(new KafkaTemplate<>(pf));
		AtomicBoolean recovererShouldFail = new AtomicBoolean(true);
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dlTemplate) {

			@Override
			public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
				if (recovererShouldFail.getAndSet(false)) {
					throw new RuntimeException("test recoverer failure");
				}
				super.accept(record, consumer, exception);
				recoverLatch.countDown();
			}

		};
		DefaultAfterRollbackProcessor<Object, Object> afterRollbackProcessor =
				spy(new DefaultAfterRollbackProcessor<>(recoverer, new FixedBackOff(0L, 2L), dlTemplate, true));
		afterRollbackProcessor.setResetStateOnRecoveryFailure(false);
		container.setAfterRollbackProcessor(afterRollbackProcessor);
		final CountDownLatch stopLatch = new CountDownLatch(1);
		container.setApplicationEventPublisher(e -> {
			if (e instanceof ConsumerStoppedEvent) {
				stopLatch.countDown();
			}
		});
		container.start();

		template.setDefaultTopic(topic3);
		template.executeInTransaction(t -> {
			RecordHeaders headers = new RecordHeaders(new RecordHeader[] { new RecordHeader("baz", "qux".getBytes()) });
			ProducerRecord<Object, Object> record = new ProducerRecord<>(topic3, 0, 0, "foo", headers);
			template.send(record);
			template.sendDefault(0, 0, "bar");
			return null;
		});
		assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
		assertThat(data.get()).isEqualTo("bar");
		assertThat(recoverLatch.await(10, TimeUnit.SECONDS)).isTrue();
		container.stop();
		Consumer<Integer, String> consumer = cf.createConsumer();
		embeddedKafka.consumeFromAnEmbeddedTopic(consumer, topic3DLT);
		ConsumerRecord<Integer, String> dltRecord = KafkaTestUtils.getSingleRecord(consumer, topic3DLT);
		assertThat(dltRecord.value()).isEqualTo("foo");
		DefaultKafkaHeaderMapper mapper = new DefaultKafkaHeaderMapper();
		Map<String, Object> map = new HashMap<>();
		mapper.toHeaders(dltRecord.headers(), map);
		MessageHeaders headers = new MessageHeaders(map);
		assertThat(new String(headers.get(KafkaHeaders.DLT_EXCEPTION_FQCN, byte[].class)))
				.contains("ListenerExecutionFailedException");
		assertThat(new String(headers.get(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, byte[].class)))
				.isEqualTo("java.lang.RuntimeException");
		assertThat(new String(headers.get(KafkaHeaders.DLT_EXCEPTION_MESSAGE, byte[].class)))
				.contains("Listener failed; fail for max failures");
		assertThat(headers.get(KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNotNull();
		assertThat(headers.get(KafkaHeaders.DLT_EXCEPTION_STACKTRACE, byte[].class))
				.contains("fail for max failures".getBytes());
		assertThat(headers.get(KafkaHeaders.DLT_ORIGINAL_OFFSET, byte[].class)[7]).isEqualTo((byte) 0);
		assertThat(headers.get(KafkaHeaders.DLT_ORIGINAL_PARTITION, byte[].class)[3]).isEqualTo((byte) 0);
		assertThat(headers.get(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP, byte[].class)).isNotNull();
		assertThat(headers.get(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP_TYPE, byte[].class)).isNotNull();
		assertThat(headers.get(KafkaHeaders.DLT_ORIGINAL_TOPIC, byte[].class)).isEqualTo(topic3.getBytes());
		assertThat(headers.get(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)).isEqualTo(group.getBytes());
		assertThat(headers.get("baz")).isEqualTo("qux".getBytes());
		pf.destroy();
		assertThat(stopLatch.await(10, TimeUnit.SECONDS)).isTrue();
		verify(afterRollbackProcessor, times(4)).isProcessInTransaction();
		ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
		verify(afterRollbackProcessor, times(4)).process(any(), any(), any(), captor.capture(), anyBoolean(), any());
		assertThat(captor.getValue()).isInstanceOf(ListenerExecutionFailedException.class)
				.extracting(ex -> ((ListenerExecutionFailedException) ex).getGroupId())
				.isEqualTo(group);
		verify(afterRollbackProcessor).clearThreadState();
		verify(dlTemplate).send(any(ProducerRecord.class));
		verify(dlTemplate).sendOffsetsToTransaction(
				eq(Collections.singletonMap(new TopicPartition(topic3, 0), new OffsetAndMetadata(1L))),
				any(ConsumerGroupMetadata.class));
	}

	@SuppressWarnings({ "unchecked"})
	@Test
	public void testBatchListenerMaxFailuresOnRecover() throws Exception {
		String group = "groupInARBP2";
		Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, group, false);
		props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
		DefaultKafkaConsumerFactory<Integer, String> cf = new DefaultKafkaConsumerFactory<>(props);
		ContainerProperties containerProps = new ContainerProperties(topic8);
		containerProps.setPollTimeout(10_000);
		containerProps.setBatchRecoverAfterRollback(true);

		Map<String, Object> senderProps = KafkaTestUtils.producerProps(embeddedKafka);
		DefaultKafkaProducerFactory<Object, Object> pf = new DefaultKafkaProducerFactory<>(senderProps);
		pf.setTransactionIdPrefix("maxAtt.batchListener");
		final KafkaTemplate<Object, Object> template = new KafkaTemplate<>(pf);
		containerProps.setMessageListener((BatchMessageListener<Integer, String>) recordList -> {
			for (ConsumerRecord<Integer, String> record : recordList) {
				if (record.offset() == 1) {
					throw new BatchListenerFailedException("fail for max failures", record);
				}
			}
		});

		KafkaTransactionManager<Object, Object> tm = new KafkaTransactionManager<>(pf);
		containerProps.setKafkaAwareTransactionManager(tm);
		KafkaMessageListenerContainer<Integer, String> container =
				new KafkaMessageListenerContainer<>(cf, containerProps);
		container.setBeanName("testBatchListenerMaxFailures");
		final CountDownLatch recoverLatch = new CountDownLatch(5);
		final KafkaOperations<Object, Object> dlTemplate = spy(new KafkaTemplate<>(pf));
		AtomicBoolean recovererShouldFail = new AtomicBoolean(true);
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dlTemplate) {

			@Override
			public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
				if (record.offset() == 1 && recovererShouldFail.getAndSet(false)) {
					throw new RuntimeException("test recoverer failure");
				}
				super.accept(record, consumer, exception);
				recoverLatch.countDown();
			}

		};
		DefaultAfterRollbackProcessor<Object, Object> afterRollbackProcessor =
				spy(new DefaultAfterRollbackProcessor<>(recoverer, new FixedBackOff(0L, 2L), dlTemplate, true));
		afterRollbackProcessor.setResetStateOnRecoveryFailure(false);
		container.setAfterRollbackProcessor(afterRollbackProcessor);
		final CountDownLatch stopLatch = new CountDownLatch(1);
		container.setApplicationEventPublisher(e -> {
			if (e instanceof ConsumerStoppedEvent) {
				stopLatch.countDown();
			}
		});
		container.start();

		template.setDefaultTopic(topic8);
		template.executeInTransaction(t -> {
			RecordHeaders headers = new RecordHeaders(new RecordHeader[] { new RecordHeader("baz", "qux".getBytes()) });
			ProducerRecord<Object, Object> record = new ProducerRecord<>(topic8, 0, 0, "bar", headers);
			template.sendDefault(0, 0, "foo");
			template.send(record);
			template.sendDefault(0, 0, "baz");
			template.sendDefault(0, 0, "quz");
			return null;
		});
		assertThat(recoverLatch.await(1000, TimeUnit.SECONDS)).isTrue();
		container.stop();
		Consumer<Integer, String> consumer = cf.createConsumer();
		embeddedKafka.consumeFromAnEmbeddedTopic(consumer, topic8DLT);
		ConsumerRecords<Integer, String> dltRecords = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(60));
		List<ConsumerRecord<Integer, String>> recordList = new ArrayList<>();
		for (ConsumerRecord<Integer, String> record : dltRecords) {
			recordList.add(record);
		}
		assertThat(recordList.size()).isEqualTo(4);
		ConsumerRecord<Integer, String> dltRecord0 = recordList.get(0);
		assertThat(dltRecord0.value()).isEqualTo("foo");
		ConsumerRecord<Integer, String> dltRecord1 = recordList.get(1);
		assertThat(dltRecord1.value()).isEqualTo("bar");
		DefaultKafkaHeaderMapper mapper = new DefaultKafkaHeaderMapper();
		Map<String, Object> map = new HashMap<>();
		mapper.toHeaders(dltRecord1.headers(), map);
		MessageHeaders headers = new MessageHeaders(map);
		assertThat(new String(headers.get(KafkaHeaders.DLT_EXCEPTION_FQCN, byte[].class)))
				.contains("ListenerExecutionFailedException");
		assertThat(new String(headers.get(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN, byte[].class)))
				.isEqualTo("org.springframework.kafka.listener.BatchListenerFailedException");
		assertThat(new String(headers.get(KafkaHeaders.DLT_EXCEPTION_MESSAGE, byte[].class)))
				.contains("Listener failed; fail for max failures");
		assertThat(headers.get(KafkaHeaders.DLT_EXCEPTION_STACKTRACE)).isNotNull();
		assertThat(headers.get(KafkaHeaders.DLT_EXCEPTION_STACKTRACE, byte[].class))
				.contains("fail for max failures".getBytes());
		assertThat(headers.get(KafkaHeaders.DLT_ORIGINAL_OFFSET, byte[].class)[7]).isEqualTo((byte) 1);
		assertThat(headers.get(KafkaHeaders.DLT_ORIGINAL_PARTITION, byte[].class)[3]).isEqualTo((byte) 0);
		assertThat(headers.get(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP, byte[].class)).isNotNull();
		assertThat(headers.get(KafkaHeaders.DLT_ORIGINAL_TIMESTAMP_TYPE, byte[].class)).isNotNull();
		assertThat(headers.get(KafkaHeaders.DLT_ORIGINAL_TOPIC, byte[].class)).isEqualTo(topic8.getBytes());
		assertThat(headers.get(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)).isEqualTo(group.getBytes());
		assertThat(headers.get("baz")).isEqualTo("qux".getBytes());
		ConsumerRecord<Integer, String> dltRecord2 = recordList.get(2);
		assertThat(dltRecord2.value()).isEqualTo("baz");
		ConsumerRecord<Integer, String> dltRecord3 = recordList.get(3);
		assertThat(dltRecord3.value()).isEqualTo("quz");
		pf.destroy();
		assertThat(stopLatch.await(10, TimeUnit.SECONDS)).isTrue();
		verify(afterRollbackProcessor, times(4)).isProcessInTransaction();
		ArgumentCaptor<Exception> captor = ArgumentCaptor.forClass(Exception.class);
		verify(afterRollbackProcessor, times(4)).processBatch(any(), any(), any(), any(), captor.capture(), anyBoolean(), any());
		assertThat(captor.getValue()).isInstanceOf(ListenerExecutionFailedException.class)
				.extracting(ex -> ((ListenerExecutionFailedException) ex).getGroupId())
				.isEqualTo(group);
		verify(afterRollbackProcessor, times(2)).clearThreadState();
		verify(dlTemplate, times(5)).send(any(ProducerRecord.class));
		verify(dlTemplate).sendOffsetsToTransaction(
				eq(Collections.singletonMap(new TopicPartition(topic8, 0), new OffsetAndMetadata(4L))),
				any(ConsumerGroupMetadata.class));
	}

	@SuppressWarnings("unchecked")
	@Test
	public void testRollbackProcessorCrash() throws Exception {
		Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, "testRollbackNoRetries", false);
		props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
		DefaultKafkaConsumerFactory<Integer, String> cf = new DefaultKafkaConsumerFactory<>(props);
		ContainerProperties containerProps = new ContainerProperties(topic4);
		containerProps.setPollTimeout(10_000);

		Map<String, Object> senderProps = KafkaTestUtils.producerProps(embeddedKafka);
		DefaultKafkaProducerFactory<Object, Object> pf = new DefaultKafkaProducerFactory<>(senderProps);
		pf.setTransactionIdPrefix("noRetries.");
		final KafkaTemplate<Object, Object> template = new KafkaTemplate<>(pf);
		final CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<String> data = new AtomicReference<>();
		containerProps.setMessageListener((MessageListener<Integer, String>) message -> {
			data.set(message.value());
			if (message.offset() == 0) {
				throw new RuntimeException("fail for no retry");
			}
			latch.countDown();
		});

		@SuppressWarnings({ "rawtypes" })
		KafkaTransactionManager tm = new KafkaTransactionManager(pf);
		containerProps.setKafkaAwareTransactionManager(tm);
		KafkaMessageListenerContainer<Integer, String> container =
				new KafkaMessageListenerContainer<>(cf, containerProps);
		container.setBeanName("testRollbackNoRetries");
		AtomicBoolean recovererShouldFail = new AtomicBoolean(true);
		BiConsumer<ConsumerRecord<?, ?>, Exception> recoverer = (rec, ex) -> {
			if (recovererShouldFail.getAndSet(false)) {
				throw new RuntimeException("arbp fail");
			}
		};
		DefaultAfterRollbackProcessor<Object, Object> afterRollbackProcessor =
				spy(new DefaultAfterRollbackProcessor<>(recoverer, new FixedBackOff(0L, 0L)));
		container.setAfterRollbackProcessor(afterRollbackProcessor);
		final CountDownLatch stopLatch = new CountDownLatch(1);
		container.setApplicationEventPublisher(e -> {
			if (e instanceof ConsumerStoppedEvent) {
				stopLatch.countDown();
			}
		});
		container.start();

		template.setDefaultTopic(topic4);
		template.executeInTransaction(t -> {
			RecordHeaders headers = new RecordHeaders(new RecordHeader[] { new RecordHeader("baz", "qux".getBytes()) });
			ProducerRecord<Object, Object> record = new ProducerRecord<>(topic4, 0, 0, "foo", headers);
			template.send(record);
			template.sendDefault(0, 0, "bar");
			return null;
		});
		assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
		assertThat(data.get()).isEqualTo("bar");
		container.stop();
		pf.destroy();
		assertThat(stopLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@Test
	public void testBatchListenerRecoverAfterRollbackProcessorCrash() throws Exception {
		String group = "testBatchListenerRollbackNoRetries";
		Map<String, Object> props = KafkaTestUtils.consumerProps(embeddedKafka, group, false);
		props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
		props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2);
		DefaultKafkaConsumerFactory<Integer, String> cf = new DefaultKafkaConsumerFactory<>(props);
		ContainerProperties containerProps = new ContainerProperties(topic9);
		containerProps.setPollTimeout(10_000);
		containerProps.setBatchRecoverAfterRollback(true);

		Map<String, Object> senderProps = KafkaTestUtils.producerProps(embeddedKafka);
		DefaultKafkaProducerFactory<Object, Object> pf = new DefaultKafkaProducerFactory<>(senderProps);
		pf.setTransactionIdPrefix("batchListener.noRetries.");
		final KafkaTemplate<Object, Object> template = new KafkaTemplate<>(pf);
		final CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<List<ConsumerRecord<Integer, String>>> data = new AtomicReference<>();
		containerProps.setMessageListener((BatchMessageListener<Integer, String>) recordList -> {
			for (ConsumerRecord<Integer, String> record : recordList) {
				if (record.offset() == 0) {
					throw new BatchListenerFailedException("fail for no retry", record);
				}
			}
			data.set(recordList);
			latch.countDown();
		});

		KafkaTransactionManager<Object, Object> tm = new KafkaTransactionManager<>(pf);
		containerProps.setKafkaAwareTransactionManager(tm);
		KafkaMessageListenerContainer<Integer, String> container =
				new KafkaMessageListenerContainer<>(cf, containerProps);
		container.setBeanName("testBatchListenerRollbackNoRetries");
		final KafkaOperations<Object, Object> dlTemplate = new KafkaTemplate<>(pf);
		AtomicBoolean recovererShouldFail = new AtomicBoolean(true);
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dlTemplate) {
			@Override
			public void accept(ConsumerRecord<?, ?> record, Consumer<?, ?> consumer, Exception exception) {
				if (recovererShouldFail.getAndSet(false)) {
					throw new RuntimeException("batch listener arbp fail");
				}
			}

		};
		DefaultAfterRollbackProcessor<Object, Object> afterRollbackProcessor =
				new DefaultAfterRollbackProcessor<>(recoverer, new FixedBackOff(0L, 0L), dlTemplate, true);
		container.setAfterRollbackProcessor(afterRollbackProcessor);
		final CountDownLatch stopLatch = new CountDownLatch(1);
		container.setApplicationEventPublisher(e -> {
			if (e instanceof ConsumerStoppedEvent) {
				stopLatch.countDown();
			}
		});
		container.start();

		template.setDefaultTopic(topic9);
		template.executeInTransaction(t -> {
			RecordHeaders headers = new RecordHeaders(new RecordHeader[] { new RecordHeader("baz", "qux".getBytes()) });
			ProducerRecord<Object, Object> record = new ProducerRecord<>(topic9, 0, 0, "foo", headers);
			template.send(record);
			template.sendDefault(0, 0, "bar");
			template.sendDefault(0, 0, "baz");
			template.sendDefault(0, 0, "qux");
			return null;
		});

		assertThat(latch.await(60, TimeUnit.SECONDS)).isTrue();
		assertThat(data.get()).isNotNull();
		ConsumerRecord<Integer, String> crBaz = data.get().get(0);
		ConsumerRecord<Integer, String> crQux = data.get().get(1);
		assertThat(crBaz.offset()).isEqualTo(2L);
		assertThat(crBaz.value()).isEqualTo("baz");
		assertThat(crQux.offset()).isEqualTo(3L);
		assertThat(crQux.value()).isEqualTo("qux");

		container.stop();
		pf.destroy();
		assertThat(stopLatch.await(10, TimeUnit.SECONDS)).isTrue();
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	@Test
	void testNoAfterRollbackWhenFenced() throws Exception {
		Consumer consumer = mock(Consumer.class);
		final TopicPartition topicPartition0 = new TopicPartition("foo", 0);
		final TopicPartition topicPartition1 = new TopicPartition("foo", 1);
		Map<TopicPartition, List<ConsumerRecord<String, String>>> recordMap = new HashMap<>();
		recordMap.put(topicPartition0, Collections.singletonList(new ConsumerRecord<>("foo", 0, 0, "key", "value")));
		recordMap.put(topicPartition1, Collections.singletonList(new ConsumerRecord<>("foo", 1, 0, "key", "value")));
		ConsumerRecords records = new ConsumerRecords(recordMap, Map.of());
		final AtomicBoolean done = new AtomicBoolean();
		final CountDownLatch pollLatch = new CountDownLatch(2);
		willAnswer(i -> {
			pollLatch.countDown();
			if (done.compareAndSet(false, true)) {
				return records;
			}
			else {
				Thread.sleep(500);
				return null;
			}
		}).given(consumer).poll(any(Duration.class));
		ConsumerFactory cf = mock(ConsumerFactory.class);
		willReturn(consumer).given(cf).createConsumer("group", "", null, KafkaTestUtils.defaultPropertyOverrides());
		Producer producer = mock(Producer.class);
		willThrow(new ProducerFencedException("test")).given(producer).commitTransaction();
		ProducerFactory pf = mock(ProducerFactory.class);
		given(pf.transactionCapable()).willReturn(true);
		given(pf.createProducer(isNull())).willReturn(producer);
		KafkaTransactionManager tm = new KafkaTransactionManager(pf);
		ContainerProperties props = new ContainerProperties(new TopicPartitionOffset("foo", 0),
				new TopicPartitionOffset("foo", 1));
		props.setGroupId("group");
		props.setKafkaAwareTransactionManager(tm);
		DefaultTransactionDefinition def = new DefaultTransactionDefinition();
		def.setTimeout(42);
		def.setName("myTx");
		props.setTransactionDefinition(def);
		AtomicInteger deliveryCount = new AtomicInteger();
		props.setMessageListener((MessageListener) m -> {
			deliveryCount.incrementAndGet();
		});
		KafkaMessageListenerContainer container = new KafkaMessageListenerContainer<>(cf, props);
		AfterRollbackProcessor arp = mock(AfterRollbackProcessor.class);
		given(arp.isProcessInTransaction()).willReturn(true);
		container.setAfterRollbackProcessor(arp);
		container.setBeanName("rollback");
		container.start();
		assertThat(pollLatch.await(10, TimeUnit.SECONDS)).isTrue();
		InOrder inOrder = inOrder(producer);
		inOrder.verify(producer).beginTransaction();
		inOrder.verify(producer).commitTransaction();
		inOrder.verify(producer).close(any());
		inOrder.verifyNoMoreInteractions();
		assertThat(deliveryCount.get()).isEqualTo(1);

		verify(arp, never()).process(any(), any(), any(), any(), anyBoolean(), any());

		assertThat(KafkaTestUtils.getPropertyValue(container,
				"listenerConsumer.transactionTemplate.timeout", Integer.class))
				.isEqualTo(42);
		assertThat(KafkaTestUtils.getPropertyValue(container,
				"listenerConsumer.transactionTemplate.name", String.class))
				.isEqualTo("myTx");
		container.stop();
		def.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
		assertThatIllegalStateException().isThrownBy(container::start);
	}

	@Test
	void testArbpWithoutRecovery() throws InterruptedException {
		// init producer
		Map<String, Object> producerProperties = KafkaTestUtils.producerProps(embeddedKafka);
		DefaultKafkaProducerFactory<Object, Object> pf = new DefaultKafkaProducerFactory<>(producerProperties);
		pf.setTransactionIdPrefix("testArbpResetWithoutRecover.batchListener");
		final KafkaTemplate<Object, Object> template = new KafkaTemplate<>(pf);
		// init consumer
		String group = "groupInARBP3";
		Map<String, Object> consumerProperties = KafkaTestUtils.consumerProps(embeddedKafka, group, false);
		consumerProperties.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
		DefaultKafkaConsumerFactory<Integer, String> cf = new DefaultKafkaConsumerFactory<>(consumerProperties);
		ContainerProperties containerProps = new ContainerProperties(topic10);
		containerProps.setPollTimeout(10_000);
		containerProps.setBatchRecoverAfterRollback(false); // we want to test the behavior if recovery is disabled
		final var successLatch = new AtomicReference<>(new CountDownLatch(2));
		containerProps.setMessageListener(new BatchMessageListener<Integer, String>() {
			private int attempt = 0;

			@Override
			public void onMessage(List<ConsumerRecord<Integer, String>> data) {
				if (3 > attempt++) { // the first three attempts should fail
					throw new BatchListenerFailedException("fail for test", data.get(0));
				}
				data.forEach(d -> successLatch.get().countDown());
			}
		});
		// init container
		KafkaTransactionManager<Object, Object> tm = new KafkaTransactionManager<>(pf);
		containerProps.setKafkaAwareTransactionManager(tm);
		KafkaMessageListenerContainer<Integer, String> container = new KafkaMessageListenerContainer<>(cf, containerProps);
		container.setBeanName("testArbpWithoutRecover");
		DefaultAfterRollbackProcessor<Object, Object> afterRollbackProcessor = spy(
				new DefaultAfterRollbackProcessor<>(new FixedBackOff(0L, FixedBackOff.UNLIMITED_ATTEMPTS))
		);
		container.setAfterRollbackProcessor(afterRollbackProcessor);
		container.start();

		// process first batch
		template.executeInTransaction(t -> {
			template.send(new ProducerRecord<>(topic10, 0, 0, "bar1"));
			template.send(new ProducerRecord<>(topic10, 0, 0, "bar2"));
			return null;
		});
		assertThat(successLatch.get().await(30, TimeUnit.SECONDS)).isTrue(); // wait for first batch

		// process second batch
		successLatch.set(new CountDownLatch(2)); // reset latch
		template.executeInTransaction(t -> {
			template.send(new ProducerRecord<>(topic10, 0, 0, "bar4"));
			template.send(new ProducerRecord<>(topic10, 0, 0, "bar5"));
			return null;
		});
		assertThat(successLatch.get().await(30, TimeUnit.SECONDS)).isTrue(); // wait for second batch

		// assert three processBatch calls due to the failed attempts + one call to clearThreadState
		verify(afterRollbackProcessor, times(3)).processBatch(any(), any(), any(), any(), any(), anyBoolean(), any());
		verify(afterRollbackProcessor).clearThreadState();

		container.stop();
	}

}
