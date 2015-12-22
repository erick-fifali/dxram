package de.uniduesseldorf.menet;

import java.net.InetSocketAddress;

public interface NodeMap {
	short getOwnNodeID();
	
	InetSocketAddress getAddress(final short p_nodeID);
}
