package org.sirix.cache;

import org.sirix.page.PageReference;
import org.sirix.page.RevisionRootPage;
import org.sirix.page.interfaces.Page;

public interface BufferManager {
  Cache<PageReference, Page> getRecordPageCache();

  Cache<IndexLogKey, Page> getUnorderedKeyValuePageCache();

  Cache<PageReference, Page> getPageCache();

  Cache<Integer, RevisionRootPage> getRevisionRootPageCache();
}
