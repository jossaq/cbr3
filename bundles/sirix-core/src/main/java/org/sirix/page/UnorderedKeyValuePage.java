/*
 * Copyright (c) 2011, University of Konstanz, Distributed Systems Group All rights reserved.
 * <p>
 * Redistribution and use in source and binary forms, with or without modification, are permitted
 * provided that the following conditions are met: * Redistributions of source code must retain the
 * above copyright notice, this list of conditions and the following disclaimer. * Redistributions
 * in binary form must reproduce the above copyright notice, this list of conditions and the
 * following disclaimer in the documentation and/or other materials provided with the distribution.
 * * Neither the name of the University of Konstanz nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written permission.
 * <p>
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL <COPYRIGHT HOLDER> BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.sirix.page;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.Iterators;
import org.sirix.access.ResourceConfiguration;
import org.sirix.api.PageReadOnlyTrx;
import org.sirix.api.PageTrx;
import org.sirix.exception.SirixIOException;
import org.sirix.node.NodeKind;
import org.sirix.node.SirixDeweyID;
import org.sirix.node.interfaces.NodePersistenter;
import org.sirix.node.interfaces.Record;
import org.sirix.node.interfaces.RecordPersister;
import org.sirix.node.interfaces.immutable.ImmutableXmlNode;
import org.sirix.page.interfaces.KeyValuePage;
import org.sirix.settings.Constants;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.*;
import java.util.*;
import java.util.Map.Entry;

import static java.util.stream.Collectors.toList;
import static org.sirix.node.Utils.getVarLong;
import static org.sirix.node.Utils.putVarLong;

/**
 * <p>
 * An UnorderedKeyValuePage stores a set of records, commonly nodes in an unordered datastructure.
 * </p>
 * <p>
 * The page currently is not thread safe (might have to be for concurrent write-transactions)!
 * </p>
 */
public final class UnorderedKeyValuePage implements KeyValuePage<Long, Record> {

  private boolean mAddedReferences;

  /**
   * References to overflow pages.
   */
  private final Map<Long, PageReference> references;

  /**
   * Key of record page. This is the base key of all contained nodes.
   */
  private final long mRecordPageKey;

  /**
   * Records (must be a {@link LinkedHashMap} to provide consistent iteration order).
   */
  private final LinkedHashMap<Long, Record> mRecords;

  /**
   * Slots which have to be serialized.
   */
  private final Map<Long, byte[]> slots;

  /**
   * Dewey IDs which have to be serialized.
   */
  private final Map<SirixDeweyID, Long> deweyIDs;

  /**
   * Sirix {@link PageReadOnlyTrx}.
   */
  private final PageReadOnlyTrx pageReadTrx;

  /**
   * The kind of page (in which subtree it resides).
   */
  private final PageKind mPageKind;

  /**
   * Persistenter.
   */
  private final RecordPersister recordPersister;

  /**
   * Reference key to the previous page if any.
   */
  private long mPreviousPageRefKey;

  /**
   * The resource configuration.
   */
  private final ResourceConfiguration mResourceConfig;

  public UnorderedKeyValuePage(final PageReadOnlyTrx pageTrx, final UnorderedKeyValuePage pageToClone) {
    mAddedReferences = pageToClone.mAddedReferences;
    references = pageToClone.references;
    mRecordPageKey = pageToClone.mRecordPageKey;
    mRecords = pageToClone.mRecords;
    slots = pageToClone.slots;
    deweyIDs = pageToClone.deweyIDs;
    pageReadTrx = pageTrx;
    mPageKind = pageToClone.mPageKind;
    recordPersister = pageToClone.recordPersister;
    mPreviousPageRefKey = pageToClone.mPreviousPageRefKey;
    mResourceConfig = pageToClone.mResourceConfig;
  }

  /**
   * Constructor which initializes a new {@link UnorderedKeyValuePage}.
   *
   * @param recordPageKey base key assigned to this node page
   * @param pageKind      the kind of subtree page (NODEPAGE, PATHSUMMARYPAGE, TEXTVALUEPAGE,
   *                      ATTRIBUTEVALUEPAGE)
   * @param pageReadTrx   the page reading transaction
   */
  public UnorderedKeyValuePage(final @Nonnegative long recordPageKey, final PageKind pageKind,
      final long previousPageRefKey, final PageReadOnlyTrx pageReadTrx) {
    // Assertions instead of checkNotNull(...) checks as it's part of the
    // internal flow.
    assert recordPageKey >= 0 : "recordPageKey must not be negative!";
    assert pageReadTrx != null : "The page reading trx must not be null!";

    references = new LinkedHashMap<>();
    mRecordPageKey = recordPageKey;
    mRecords = new LinkedHashMap<>();
    slots = new LinkedHashMap<>();
    this.pageReadTrx = pageReadTrx;
    mPageKind = pageKind;
    mResourceConfig = pageReadTrx.getResourceManager().getResourceConfig();
    recordPersister = mResourceConfig.recordPersister;
    mPreviousPageRefKey = previousPageRefKey;

    if (this.pageReadTrx.getResourceManager().getResourceConfig().areDeweyIDsStored
        && recordPersister instanceof NodePersistenter) {
      deweyIDs = new LinkedHashMap<>();
    } else {
      deweyIDs = Collections.emptyMap();
    }
  }

  /**
   * Constructor which reads the {@link UnorderedKeyValuePage} from the storage.
   *
   * @param in          input bytes to read page from
   * @param pageReadTrx {@link PageReadOnlyTrx} implementation
   */
  protected UnorderedKeyValuePage(final DataInput in, final PageReadOnlyTrx pageReadTrx) throws IOException {
    mRecordPageKey = getVarLong(in);
    mResourceConfig = pageReadTrx.getResourceManager().getResourceConfig();
    recordPersister = mResourceConfig.recordPersister;
    this.pageReadTrx = pageReadTrx;
    slots = new LinkedHashMap<>();

    if (mResourceConfig.areDeweyIDsStored && recordPersister instanceof NodePersistenter) {
      deweyIDs = new LinkedHashMap<>();
      final NodePersistenter persistenter = (NodePersistenter) recordPersister;
      final int deweyIDSize = in.readInt();

      mRecords = new LinkedHashMap<>(deweyIDSize);
      var optionalDeweyId = Optional.<SirixDeweyID>empty();

      for (int index = 0; index < deweyIDSize; index++) {
        optionalDeweyId = persistenter.deserializeDeweyID(in, optionalDeweyId.orElse(null), mResourceConfig);

        optionalDeweyId.ifPresent(deweyId -> deserializeRecordAndPutIntoMap(in, deweyId));
      }
    } else {
      deweyIDs = Collections.emptyMap();
      mRecords = new LinkedHashMap<>();
    }

    final var entriesBitmap = SerializationType.deserializeBitSet(in);
    final var overlongEntriesBitmap = SerializationType.deserializeBitSet(in);

    final int normalEntrySize = in.readInt();
    var setBit = -1;
    for (int index = 0; index < normalEntrySize; index++) {
      setBit = entriesBitmap.nextSetBit(setBit + 1);
      assert setBit >= 0;
      final long key = mRecordPageKey * Constants.NDP_NODE_COUNT + setBit;
      final int dataSize = in.readInt();
      final byte[] data = new byte[dataSize];
      in.readFully(data);
      final Record record =
          recordPersister.deserialize(new DataInputStream(new ByteArrayInputStream(data)), key, null, this.pageReadTrx);
      mRecords.put(key, record);
    }

    final int overlongEntrySize = in.readInt();
    references = new LinkedHashMap<>(overlongEntrySize);
    setBit = -1;
    for (int index = 0; index < overlongEntrySize; index++) {
      setBit = overlongEntriesBitmap.nextSetBit(setBit + 1);
      assert setBit >= 0;
      final long key = mRecordPageKey * Constants.NDP_NODE_COUNT + setBit;
      final PageReference reference = new PageReference();
      reference.setKey(in.readLong());
      references.put(key, reference);
    }
    assert pageReadTrx != null : "pageReadTrx must not be null!";
    final boolean hasPreviousReference = in.readBoolean();
    if (hasPreviousReference) {
      mPreviousPageRefKey = in.readLong();
    } else {
      mPreviousPageRefKey = Constants.NULL_ID_LONG;
    }
    mPageKind = PageKind.getKind(in.readByte());
  }

  private void deserializeRecordAndPutIntoMap(DataInput in, SirixDeweyID deweyId) {
    try {
      final long key = getVarLong(in);
      final int dataSize = in.readInt();
      final byte[] data = new byte[dataSize];
      in.readFully(data);
      final Record record =
          recordPersister.deserialize(new DataInputStream(new ByteArrayInputStream(data)), key, deweyId, pageReadTrx);
      mRecords.put(key, record);
    } catch (final IOException e) {
      throw new SirixIOException(e);
    }
  }

  @Override
  public long getPageKey() {
    return mRecordPageKey;
  }

  @Override
  public Record getValue(final Long key) {
    assert key != null : "key must not be null!";
    Record record = mRecords.get(key);
    if (record == null) {
      byte[] data;
      try {
        final PageReference reference = references.get(key);
        if (reference != null && reference.getKey() != Constants.NULL_ID_LONG) {
          data = ((OverflowPage) pageReadTrx.getReader().read(reference, pageReadTrx)).getData();
        } else {
          return null;
        }
      } catch (final SirixIOException e) {
        return null;
      }
      final InputStream in = new ByteArrayInputStream(data);
      try {
        record = recordPersister.deserialize(new DataInputStream(in), key, null, null);
      } catch (final IOException e) {
        return null;
      }
      mRecords.put(key, record);
    }
    return record;
  }

  @Override
  public void setEntry(final Long key, @Nonnull final Record value) {
    assert value != null : "record must not be null!";
    mAddedReferences = false;
    mRecords.put(key, value);
  }

  @Override
  public void serialize(final DataOutput out, final SerializationType type) throws IOException {
    if (!mAddedReferences) {
      addReferences();
    }
    // Write page key.
    putVarLong(out, mRecordPageKey);
    // Write dewey IDs.
    if (mResourceConfig.areDeweyIDsStored && recordPersister instanceof NodePersistenter) {
      final var persistence = (NodePersistenter) recordPersister;
      out.writeInt(deweyIDs.size());
      final List<SirixDeweyID> ids = new ArrayList<>(deweyIDs.keySet());
      ids.sort(Comparator.comparingInt((SirixDeweyID sirixDeweyID) -> sirixDeweyID.toBytes().length));
      final var iter = Iterators.peekingIterator(ids.iterator());
      SirixDeweyID id = null;
      if (iter.hasNext()) {
        id = iter.next();
        persistence.serializeDeweyID(out, NodeKind.ELEMENT, id, null, mResourceConfig);
        serializeDeweyRecord(id, out);
      }
      while (iter.hasNext()) {
        final var nextDeweyID = iter.next();
        persistence.serializeDeweyID(out, NodeKind.ELEMENT, id, nextDeweyID, mResourceConfig);
        serializeDeweyRecord(nextDeweyID, out);
        id = nextDeweyID;
      }
    }

    final var entriesBitmap = new BitSet(Constants.NDP_NODE_COUNT);
    final var entriesSortedByKey = slots.entrySet().stream().sorted(Entry.comparingByKey()).collect(toList());
    for (final Entry<Long, byte[]> entry : entriesSortedByKey) {
      final var pageOffset = pageReadTrx.recordPageOffset(entry.getKey());
      entriesBitmap.set(pageOffset);
    }
    SerializationType.serializeBitSet(out, entriesBitmap);

    final var overlongEntriesBitmap = new BitSet(Constants.NDP_NODE_COUNT);
    final var overlongEntriesSortedByKey =
        references.entrySet().stream().sorted(Entry.comparingByKey()).collect(toList());
    for (final Map.Entry<Long, PageReference> entry : overlongEntriesSortedByKey) {
      final var pageOffset = pageReadTrx.recordPageOffset(entry.getKey());
      overlongEntriesBitmap.set(pageOffset);
    }
    SerializationType.serializeBitSet(out, overlongEntriesBitmap);

    // Write normal entries.
    out.writeInt(entriesSortedByKey.size());
    for (final var entry : entriesSortedByKey) {
      final byte[] data = entry.getValue();
      final int length = data.length;
      out.writeInt(length);
      out.write(data);
    }

    // Write overlong entries.
    out.writeInt(overlongEntriesSortedByKey.size());
    for (final var entry : overlongEntriesSortedByKey) {
      // Write key in persistent storage.
      out.writeLong(entry.getValue().getKey());
    }

    // Write previous reference if it has any reference.
    final var hasPreviousReference = mPreviousPageRefKey != Constants.NULL_ID_LONG;
    out.writeBoolean(hasPreviousReference);
    if (hasPreviousReference) {
      out.writeLong(mPreviousPageRefKey);
    }
    out.writeByte(mPageKind.getID());
  }

  private void serializeDeweyRecord(SirixDeweyID id, DataOutput out) throws IOException {
    final long recordKey = deweyIDs.get(id);
    putVarLong(out, recordKey);
    final byte[] data = slots.get(recordKey);
    final int length = data.length;
    out.writeInt(length);
    out.write(data);
    slots.remove(recordKey);
  }

  @Override
  public String toString() {
    final MoreObjects.ToStringHelper helper = MoreObjects.toStringHelper(this).add("pagekey", mRecordPageKey);
    for (final Record record : mRecords.values()) {
      helper.add("record", record);
    }
    for (final PageReference reference : references.values()) {
      helper.add("reference", reference);
    }
    return helper.toString();
  }

  @Override
  public Set<Entry<Long, Record>> entrySet() {
    return mRecords.entrySet();
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(mRecordPageKey, mRecords, references);
  }

  @Override
  public boolean equals(final @Nullable Object obj) {
    if (obj instanceof UnorderedKeyValuePage) {
      final UnorderedKeyValuePage other = (UnorderedKeyValuePage) obj;
      return mRecordPageKey == other.mRecordPageKey && Objects.equal(mRecords, other.mRecords) && Objects.equal(
          references, other.references);
    }
    return false;
  }

  @Override
  public List<PageReference> getReferences() {
    throw new UnsupportedOperationException();
  }

  @Override
  public <K extends Comparable<? super K>, V extends Record, S extends KeyValuePage<K, V>> void commit(
      @Nonnull PageTrx<K, V, S> pageWriteTrx) {
    if (!mAddedReferences) {
      try {
        addReferences();
      } catch (final IOException e) {
        throw new SirixIOException(e);
      }
    }

    for (final PageReference reference : references.values()) {
      if (!(reference.getPage() == null && reference.getKey() == Constants.NULL_ID_LONG
          && reference.getLogKey() == Constants.NULL_ID_LONG)) {
        pageWriteTrx.commit(reference);
      }
    }
  }

  // Add references to OverflowPages.
  private void addReferences() throws IOException {
    final var storeDeweyIDs = pageReadTrx.getResourceManager().getResourceConfig().areDeweyIDsStored;

    final var entries = sort();
    for (final var entry : entries) {
      final var record = entry.getValue();
      final var recordID = record.getNodeKey();
      if (slots.get(recordID) == null) {
        // Must be either a normal record or one which requires an
        // Overflow page.
        final var output = new ByteArrayOutputStream();
        final var out = new DataOutputStream(output);
        recordPersister.serialize(out, record, pageReadTrx);
        final var data = output.toByteArray();
        if (data.length > PageConstants.MAX_RECORD_SIZE) {
          final var reference = new PageReference();
          reference.setPage(new OverflowPage(data));
          references.put(recordID, reference);
        } else {
          if (storeDeweyIDs && recordPersister instanceof NodePersistenter && record instanceof ImmutableXmlNode
              && ((ImmutableXmlNode) record).getDeweyID().isPresent() && record.getNodeKey() != 0)
            deweyIDs.put(((ImmutableXmlNode) record).getDeweyID().get(), record.getNodeKey());
          slots.put(recordID, data);
        }
      }
    }

    mAddedReferences = true;
  }

  private List<Entry<Long, Record>> sort() {
    // Sort entries which have deweyIDs according to their byte-length.
    final List<Map.Entry<Long, Record>> entries = new ArrayList<>(mRecords.entrySet());
    final boolean storeDeweyIDs = pageReadTrx.getResourceManager().getResourceConfig().areDeweyIDsStored;
    if (storeDeweyIDs && recordPersister instanceof NodePersistenter) {
      entries.sort((a, b) -> {
        if (a.getValue() instanceof ImmutableXmlNode && b.getValue() instanceof ImmutableXmlNode) {
          final Optional<SirixDeweyID> first = ((ImmutableXmlNode) a.getValue()).getDeweyID();
          final Optional<SirixDeweyID> second = ((ImmutableXmlNode) b.getValue()).getDeweyID();

          // Document node has no DeweyID.
          if (first.isEmpty() && second.isPresent())
            return 1;

          if (second.isEmpty() && first.isPresent())
            return -1;

          if (first.isEmpty())
            return 0;

          return first.get().compareTo(second.get());
        }

        return -1;
      });
    }

    return entries;
  }

  @Override
  public Collection<Record> values() {
    return mRecords.values();
  }

  @Override
  public PageReference getReference(int offset) {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean setReference(int offset, PageReference pageReference) {
    throw new UnsupportedOperationException();
  }

  @Override
  public PageReadOnlyTrx getPageReadTrx() {
    return pageReadTrx;
  }

  @SuppressWarnings("unchecked")
  @Override
  public <C extends KeyValuePage<Long, Record>> C newInstance(final long recordPageKey, @Nonnull final PageKind pageKind,
      final long previousPageRefKey, @Nonnull final PageReadOnlyTrx pageReadTrx) {
    return (C) new UnorderedKeyValuePage(recordPageKey, pageKind, previousPageRefKey, pageReadTrx);
  }

  @Override
  public PageKind getPageKind() {
    return mPageKind;
  }

  @Override
  public int size() {
    return mRecords.size() + references.size();
  }

  @Override
  public void setPageReference(final Long key, @Nonnull final PageReference reference) {
    assert key != null;
    references.put(key, reference);
  }

  @Override
  public Set<Entry<Long, PageReference>> referenceEntrySet() {
    return references.entrySet();
  }

  @Override
  public PageReference getPageReference(final Long key) {
    assert key != null;
    return references.get(key);
  }

  @Override
  public long getPreviousReferenceKey() {
    return mPreviousPageRefKey;
  }

}
