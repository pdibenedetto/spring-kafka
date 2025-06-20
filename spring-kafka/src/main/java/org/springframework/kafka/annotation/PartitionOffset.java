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

package org.springframework.kafka.annotation;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.kafka.support.TopicPartitionOffset.SeekPosition;

/**
 * Used to add partition/initial offset information to a {@code KafkaListener}.
 *
 * @author Artem Bilan
 * @author Gary Russell
 * @author Wang Zhiyang
 */
@Target({})
@Retention(RetentionPolicy.RUNTIME)
public @interface PartitionOffset {

	/**
	 * The partition within the topic to listen on. Property place holders and SpEL
	 * expressions are supported, which must resolve to Integer (or String that can be
	 * parsed as Integer). '*' indicates that the initial offset will be applied to all
	 * partitions in the encompassing {@link TopicPartition} The string can contain a
	 * comma-delimited list of partitions, or ranges of partitions (e.g.
	 * {@code 0-5, 7, 10-15}), in which case, the offset will be applied to all of those
	 * partitions.
	 * @return partition within the topic.
	 */
	String partition();

	/**
	 * The initial offset of the {@link #partition()}.
	 * Property place holders and SpEL expressions are supported,
	 * which must resolve to Long (or String that can be parsed as Long).
	 * @return initial offset.
	 */
	String initialOffset();

	/**
	 * By default, positive {@link #initialOffset()} is absolute, negative
	 * is relative to the current topic end. When this is 'true', the
	 * initial offset (positive or negative) is relative to the current
	 * consumer position.
	 * @return whether or not the offset is relative to the current position.
	 * @since 1.1
	 */
	String relativeToCurrent() default "false";

	/**
	 * Position to seek on partition assignment. By default, seek by offset.
	 * Set {@link SeekPosition} seek position enum name to specify "special"
	 * seeks, no restrictions on capitalization. If seekPosition set 'BEGINNING'
	 * or 'END', ignore {@code relativeToCurrent} and {@code initialOffset}.
	 * If seekPosition set 'TIMESTAMP', initialOffset means time stamp, ignore
	 * {@code relativeToCurrent}.
	 * @return special seeks.
	 * @since 3.2
	 * @see SeekPosition
	 */
	String seekPosition() default "";

}
