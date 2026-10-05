package emu.grasscutter.utils;

import java.io.Serializable;
import java.util.AbstractSet;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;

/** Compact, mutable integer set with snapshot iteration for scene unlock state. */
public final class CompactIntSet extends AbstractSet<Integer> implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final int DENSE_ID_LIMIT = 4096;
    private final BitSet dense = new BitSet(0);
    private HashSet<Integer> sparse;
    private boolean hasNull;
    private int size;

    public CompactIntSet() {}

    public CompactIntSet(Collection<? extends Integer> values) {
        this.addAll(values);
    }

    @Override
    public synchronized int size() {
        return this.size;
    }

    @Override
    public synchronized boolean contains(Object value) {
        if (value == null) return this.hasNull;
        if (!(value instanceof Integer id)) return false;
        if (id >= 0 && id < DENSE_ID_LIMIT) return this.dense.get(id);
        return this.sparse != null && this.sparse.contains(id);
    }

    @Override
    public synchronized boolean add(Integer id) {
        if (this.contains(id)) return false;
        if (id == null) {
            this.hasNull = true;
        } else if (id >= 0 && id < DENSE_ID_LIMIT) {
            this.dense.set(id);
        } else {
            if (this.sparse == null) this.sparse = new HashSet<>();
            this.sparse.add(id);
        }
        this.size++;
        return true;
    }

    @Override
    public synchronized boolean remove(Object value) {
        if (!this.contains(value)) return false;
        if (value == null) {
            this.hasNull = false;
        } else {
            int id = (Integer) value;
            if (id >= 0 && id < DENSE_ID_LIMIT) this.dense.clear(id);
            else this.sparse.remove(id);
        }
        this.size--;
        return true;
    }

    @Override
    public synchronized void clear() {
        this.dense.clear();
        this.sparse = null;
        this.hasNull = false;
        this.size = 0;
    }

    @Override
    public synchronized Iterator<Integer> iterator() {
        BitSet bits = (BitSet) this.dense.clone();
        Integer[] other = this.sparse == null ? new Integer[0] : this.sparse.toArray(new Integer[0]);
        boolean includeNull = this.hasNull;
        return new Iterator<>() {
            private int nextBit = bits.nextSetBit(0);
            private int index;
            private boolean nullPending = includeNull;
            private Integer current;
            private boolean canRemove;

            @Override
            public boolean hasNext() {
                return this.nextBit >= 0 || this.index < other.length || this.nullPending;
            }

            @Override
            public Integer next() {
                if (this.nextBit >= 0) {
                    this.current = this.nextBit;
                    this.nextBit = bits.nextSetBit(this.nextBit + 1);
                } else if (this.index < other.length) {
                    this.current = other[this.index++];
                } else if (this.nullPending) {
                    this.current = null;
                    this.nullPending = false;
                } else {
                    throw new NoSuchElementException();
                }
                this.canRemove = true;
                return this.current;
            }

            @Override
            public void remove() {
                if (!this.canRemove) throw new IllegalStateException();
                CompactIntSet.this.remove(this.current);
                this.canRemove = false;
            }
        };
    }
}
