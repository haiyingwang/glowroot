/**
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.informant.container.trace;

import checkers.nullness.quals.Nullable;
import com.google.common.base.Objects;
import com.google.common.collect.Ordering;
import com.google.common.primitives.Longs;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Metric {

    static final Ordering<Metric> orderingByTotal = new Ordering<Metric>() {
        @Override
        public int compare(@Nullable Metric left, @Nullable Metric right) {
            checkNotNull(left, "Ordering of non-null elements only");
            checkNotNull(right, "Ordering of non-null elements only");
            return Longs.compare(left.total, right.total);
        }
    };

    @Nullable
    private String name;
    private long total;
    private long min;
    private long max;
    private long count;
    private boolean active;
    private boolean minActive;
    private boolean maxActive;

    @Nullable
    public String getName() {
        return name;
    }

    public long getTotal() {
        return total;
    }

    public long getMin() {
        return min;
    }

    public long getMax() {
        return max;
    }

    public long getCount() {
        return count;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isMinActive() {
        return minActive;
    }

    public boolean isMaxActive() {
        return maxActive;
    }

    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("name", name)
                .add("total", total)
                .add("min", min)
                .add("max", max)
                .add("count", count)
                .add("isActive", active)
                .add("minActive", minActive)
                .add("maxActive", maxActive)
                .toString();
    }
}