/*
 * Copyright 2015 Tagir Valeev
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package javax.util.streamex;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Random;
import java.util.Spliterator;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * @author Tagir Valeev
 *
 */
public class CollapseSpliteratorTest {
    private static <T> void splitEquals(Spliterator<T> source, BiConsumer<Spliterator<T>, Spliterator<T>> consumer) {
        Spliterator<T> right = new CollapseSpliterator2<>(Objects::equals, Function.identity(),
                StreamExInternals.selectFirst(), StreamExInternals.selectFirst(), source);
        Spliterator<T> left = right.trySplit();
        assertNotNull(left);
        consumer.accept(left, right);
    }

    @Test
    public void testSimpleSplit() {
        List<Integer> input = Arrays.asList(1, 1, 1, 2, 2, 2, 2, 2);
        splitEquals(input.spliterator(), (left, right) -> {
            List<Integer> result = new ArrayList<>();
            left.forEachRemaining(result::add);
            right.forEachRemaining(result::add);
            assertEquals(Arrays.asList(1, 2), result);
        });
        splitEquals(input.spliterator(), (left, right) -> {
            List<Integer> result = new ArrayList<>();
            List<Integer> resultRight = new ArrayList<>();
            right.forEachRemaining(resultRight::add);
            left.forEachRemaining(result::add);
            result.addAll(resultRight);
            assertEquals(Arrays.asList(1, 2), result);
        });
        input = IntStreamEx.of(new Random(1), 100, 1, 10).sorted().boxed().toList();
        splitEquals(input.spliterator(), (left, right) -> {
            List<Integer> result = new ArrayList<>();
            List<Integer> resultRight = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                left.tryAdvance(result::add);
                right.tryAdvance(resultRight::add);
            }
            result.addAll(resultRight);
            assertEquals(IntStreamEx.range(1, 10).boxed().toList(), result);
        });
        input = IntStreamEx.constant(100, 100).append(2).prepend(1).boxed().toList();
        splitEquals(StreamEx.of(input).without(100).parallel().spliterator(), (left, right) -> {
            List<Integer> result = new ArrayList<>();
            left.forEachRemaining(result::add);
            right.forEachRemaining(result::add);
            assertEquals(Arrays.asList(1, 2), result);
        });
        input = Arrays.asList(0, 0, 1, 1, 1, 1, 4, 6, 6, 3, 3, 10);
        splitEquals(Stream.concat(Stream.empty(), input.parallelStream()).spliterator(), (left, right) -> {
            List<Integer> result = new ArrayList<>();
            left.forEachRemaining(result::add);
            right.forEachRemaining(result::add);
            assertEquals(Arrays.asList(0, 1, 4, 6, 3, 10), result);
        });
    }

    @Test
    public void testNonIdentity() {
        checkNonIdentity(Arrays.asList(1, 2, 5, 6, 7, 8, 10, 11, 15));
        checkNonIdentity(IntStreamEx.range(3, 100).prepend(1).boxed().toList());
    }

    private void checkNonIdentity(List<Integer> input) {
        checkSpliterator(() -> new CollapseSpliterator2<Integer, Entry<Integer, Integer>>((a, b) -> (b - a == 1),
                a -> new AbstractMap.SimpleEntry<>(a, a), (acc, a) -> new AbstractMap.SimpleEntry<>(acc.getKey(), a), (
                        a, b) -> new AbstractMap.SimpleEntry<>(a.getKey(), b.getValue()), input.spliterator()));
    }

    @Test
    public void testMultiSplit() {
        List<Integer> input = Arrays.asList(0, 0, 1, 1, 1, 1, 4, 6, 6, 3, 3, 10);
        multiSplit(input::spliterator);
        multiSplit(() -> Stream.concat(Stream.empty(), input.parallelStream()).spliterator());
    }

    private void multiSplit(Supplier<Spliterator<Integer>> inputSpliterator) throws AssertionError {
        Random r = new Random(1);
        for (int n = 1; n < 100; n++) {
            Spliterator<Integer> spliterator = new CollapseSpliterator2<>(Objects::equals, Function.identity(),
                    StreamExInternals.selectFirst(), StreamExInternals.selectFirst(), inputSpliterator.get());
            List<Integer> result = new ArrayList<>();
            List<Spliterator<Integer>> spliterators = new ArrayList<>();
            spliterators.add(spliterator);
            for (int i = 0; i < 8; i++) {
                Spliterator<Integer> split = spliterators.get(r.nextInt(spliterators.size())).trySplit();
                if (split != null)
                    spliterators.add(split);
            }
            Collections.shuffle(spliterators, r);
            for (int i = 0; i < spliterators.size(); i++) {
                try {
                    spliterators.get(i).forEachRemaining(result::add);
                } catch (AssertionError e) {
                    throw new AssertionError("at #" + i, e);
                }
            }
            assertEquals("#" + n, 6, result.size());
        }
    }

    private <T> void checkSpliterator(Supplier<Spliterator<T>> supplier) {
        List<T> expected = new ArrayList<>();
        Spliterator<T> sequential = supplier.get();
        sequential.forEachRemaining(expected::add);
        assertFalse(sequential.tryAdvance(t -> fail("Advance called with "+t)));
        sequential.forEachRemaining(t -> fail("Advance called with "+t));
        Random r = new Random(1);
        for (int n = 1; n < 1000; n++) {
            Spliterator<T> spliterator = supplier.get();
            List<Spliterator<T>> spliterators = new ArrayList<>();
            spliterators.add(spliterator);
            int p = r.nextInt(10)+2;
            for (int i = 0; i < p; i++) {
                int idx = r.nextInt(spliterators.size());
                Spliterator<T> split = spliterators.get(idx).trySplit();
                if (split != null)
                    spliterators.add(idx, split);
            }
            List<Integer> order = IntStreamEx.ofIndices(spliterators).boxed().toList();
            Collections.shuffle(order, r);
            List<T> list = StreamEx.of(order).mapToEntry(idx -> {
                Spliterator<T> s = spliterators.get(idx);
                Stream.Builder<T> builder = Stream.builder(); 
                s.forEachRemaining(builder);
                assertFalse(s.tryAdvance(t -> fail("Advance called with "+t)));
                s.forEachRemaining(t -> fail("Advance called with "+t));
                return builder.build();
            }).sortedBy(Entry::getKey).values().flatMap(Function.identity()).toList();
            assertEquals("#"+n, expected, list);
        }
        for (int n = 1; n < 1000; n++) {
            Spliterator<T> spliterator = supplier.get();
            List<Spliterator<T>> spliterators = new ArrayList<>();
            spliterators.add(spliterator);
            int p = r.nextInt(30)+2;
            for (int i = 0; i < p; i++) {
                int idx = r.nextInt(spliterators.size());
                Spliterator<T> split = spliterators.get(idx).trySplit();
                if (split != null)
                    spliterators.add(idx, split);
            }
            List<List<T>> results = StreamEx.<List<T>>generate(() -> new ArrayList<>()).limit(spliterators.size()).toList();
            int count = spliterators.size();
            while(count > 0) {
                int i;
                do {
                    i = r.nextInt(spliterators.size());
                    spliterator = spliterators.get(i);
                } while (spliterator == null);
                if(!spliterator.tryAdvance(results.get(i)::add)) {
                    spliterators.set(i, null);
                    count--;
                }
            }
            List<T> list = StreamEx.of(results).flatMap(List::stream).toList();
            assertEquals("#"+n, expected, list);
        }
    }
}
