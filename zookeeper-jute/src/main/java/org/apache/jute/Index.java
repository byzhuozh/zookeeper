/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jute;

/**
 * Interface that acts as an iterator for deserializing maps.
 * The deserializer returns an instance that the record uses to
 * read vectors and maps. An example of usage is as follows:
 *
 * <code>
 * Index idx = startVector(...);
 * while (!idx.done()) {
 *   .... // read element of a vector
 *   idx.incr();
 * }
 * </code>
 *
 * 用于迭代反序列化器的迭代器
 */
public interface Index {

    // 是否已经完成
    public boolean done();

    // 下一项
    public void incr();
}
