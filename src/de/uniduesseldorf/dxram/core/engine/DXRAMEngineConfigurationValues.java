package de.uniduesseldorf.dxram.core.engine;

import de.uniduesseldorf.utils.Pair;

public class DXRAMEngineConfigurationValues {
	
	// DXRAM role (Superpeer, Peer or Monitor)
	public static final Pair<String, String> IP = new Pair<String, String>("IP", "127.0.0.1");
	public static final Pair<String, Integer> PORT = new Pair<String, Integer>("Port", 22221);
	public static final Pair<String, String> ROLE = new Pair<String, String>("Role", "Peer");
}