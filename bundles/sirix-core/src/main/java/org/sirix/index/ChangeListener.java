package org.sirix.index;

import javax.annotation.Nonnull;
import org.sirix.access.trx.node.xml.XmlIndexController.ChangeType;
import org.sirix.node.interfaces.immutable.ImmutableNode;

public interface ChangeListener {
  void listen(ChangeType type, @Nonnull ImmutableNode node, long pathNodeKey);
}
