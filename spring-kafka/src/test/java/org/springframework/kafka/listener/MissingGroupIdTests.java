/*
 * Copyright 2018-present the original author or authors.
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

import java.util.Collections;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.springframework.context.ApplicationContextException;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.TopicPartitionOffset;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.condition.EmbeddedKafkaCondition;
import org.springframework.kafka.test.context.EmbeddedKafka;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * @author Gary Russell
 * @since 2.1.5
 *
 */
@EmbeddedKafka(topics = "missing.group")
public class MissingGroupIdTests {

	private static EmbeddedKafkaBroker embeddedKafka;

	@BeforeAll
	public static void setup() {
		embeddedKafka = EmbeddedKafkaCondition.getBroker();
	}

	@Test
	public void testContextFailsWithKafkaListener() {
		assertThatExceptionOfType(ApplicationContextException.class).isThrownBy(() -> {
				new AnnotationConfigApplicationContext(Config1.class);
		})
			.withCauseInstanceOf(IllegalStateException.class)
			.withStackTraceContaining("No group.id found in consumer config");
	}

	@Test
	public void testContextFailsWithSubscribedContainer() {
		assertThatExceptionOfType(ApplicationContextException.class).isThrownBy(() -> {
				new AnnotationConfigApplicationContext(Config2.class);
		})
			.withCauseInstanceOf(IllegalStateException.class)
			.withStackTraceContaining("No group.id found in consumer config");
	}

	@Test
	public void testContextLoadsWithAssignedContainer() {
		new AnnotationConfigApplicationContext(Config3.class).close();
	}

	@Configuration
	@EnableKafka
	public static class Config1 {

		@Bean
		public ConsumerFactory<String, String> cf() {
			return new DefaultKafkaConsumerFactory<>(
					Collections.singletonMap(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
							embeddedKafka.getBrokersAsString()),
					new StringDeserializer(), new StringDeserializer());
		}

		@Bean
		public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory() {
			ConcurrentKafkaListenerContainerFactory<String, String> factory =
					new ConcurrentKafkaListenerContainerFactory<>();
			factory.setConsumerFactory(cf());
			return factory;
		}

		@KafkaListener(topics = "missing.group")
		public void listen(String in) {
			// no op
		}

	}

	@Configuration
	@EnableKafka
	public static class Config2 {

		@Bean
		public ConsumerFactory<String, String> cf() {
			return new DefaultKafkaConsumerFactory<>(
					Collections.singletonMap(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
							embeddedKafka.getBrokersAsString()),
					new StringDeserializer(), new StringDeserializer());
		}

		@Bean
		public KafkaMessageListenerContainer<String, String> container() {
			ContainerProperties props = new ContainerProperties("missing.group");
			return new KafkaMessageListenerContainer<>(cf(), props);
		}

	}

	@Configuration
	@EnableKafka
	public static class Config3 {

		@Bean
		public ConsumerFactory<String, String> cf() {
			return new DefaultKafkaConsumerFactory<>(
					Collections.singletonMap(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
							embeddedKafka.getBrokersAsString()),
					new StringDeserializer(), new StringDeserializer());
		}

		@Bean
		public KafkaMessageListenerContainer<String, String> container() {
			ContainerProperties props = new ContainerProperties(new TopicPartitionOffset("missing.group", 0));
			props.setMessageListener((MessageListener<String, String>) r -> { });
			return new KafkaMessageListenerContainer<>(cf(), props);
		}

	}

}
