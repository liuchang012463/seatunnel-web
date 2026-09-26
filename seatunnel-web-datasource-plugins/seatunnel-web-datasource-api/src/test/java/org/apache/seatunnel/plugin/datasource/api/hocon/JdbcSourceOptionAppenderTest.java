/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.seatunnel.plugin.datasource.api.hocon;

import com.typesafe.config.ConfigFactory;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdbcSourceOptionAppenderTest {

    @Test
    void emitsPartitionBoundsAndNumericPartitionCount() {
        Map<String, Object> result = new HashMap<>();
        JdbcSourceOptionAppender appender = new JdbcSourceOptionAppender();

        appender.append(ConfigFactory.parseString(
                "partitionColumn = order_id\n"
                        + "partitionLowerBound = 1\n"
                        + "partitionUpperBound = 1000\n"
                        + "partitionNum = 4"), result);

        assertEquals("order_id", result.get("partition_column"));
        assertEquals("1", result.get("partition_lower_bound"));
        assertEquals("1000", result.get("partition_upper_bound"));
        assertEquals(4, result.get("partition_num"));
    }
}
