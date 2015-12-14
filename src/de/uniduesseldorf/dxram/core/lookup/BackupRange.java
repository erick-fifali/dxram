package de.uniduesseldorf.dxram.core.lookup;

/**
 * Stores a backup range
 * @author Kevin Beineke 10.06.2015
 */
public final class BackupRange {

	// Attributes
	private long m_firstChunkIDORRangeID;
	private short[] m_backupPeers;

	// Constructors
	/**
	 * Creates an instance of Locations
	 * @param p_firstChunkIDORRangeID
	 *            the RangeID or the first ChunkID
	 * @param p_backupPeers
	 *            the backup peers
	 */
	public BackupRange(final long p_firstChunkIDORRangeID, final short[] p_backupPeers) {
		super();

		m_firstChunkIDORRangeID = p_firstChunkIDORRangeID;
		m_backupPeers = p_backupPeers;
	}

	/**
	 * Creates an instance of Locations
	 * @param p_firstChunkIDORRangeID
	 *            the RangeID or the first ChunkID
	 * @param p_backupPeers
	 *            the locations in long representation
	 */
	public BackupRange(final long p_firstChunkIDORRangeID, final long p_backupPeers) {
		this(p_firstChunkIDORRangeID, new short[] {(short) (p_backupPeers & 0x000000000000FFFFL),
				(short) ((p_backupPeers & 0x00000000FFFF0000L) >> 16), (short) ((p_backupPeers & 0x0000FFFF00000000L) >> 32)});
	}

	// Getter
	/**
	 * Returns RangeID or first ChunkID
	 * @return RangeID or first ChunkID
	 */
	public long getRangeID() {
		return m_firstChunkIDORRangeID;
	}

	/**
	 * Get backup peers
	 * @return the backup peers
	 */
	public short[] getBackupPeers() {
		return m_backupPeers;
	}

	/**
	 * Get backup peers as long
	 * @return the backup peers
	 */
	public long getBackupPeersAsLong() {
		long ret = -1;
		if (null != m_backupPeers) {
			if (m_backupPeers.length == 3) {
				ret =
						((m_backupPeers[2] & 0x000000000000FFFFL) << 32) + ((m_backupPeers[1] & 0x000000000000FFFFL) << 16)
								+ (m_backupPeers[0] & 0x000000000000FFFFL);
			} else if (m_backupPeers.length == 2) {
				ret = ((-1 & 0x000000000000FFFFL) << 32) + ((m_backupPeers[1] & 0x000000000000FFFFL) << 16) + (m_backupPeers[0] & 0x000000000000FFFFL);
			} else {
				ret = ((-1 & 0x000000000000FFFFL) << 32) + ((-1 & 0x000000000000FFFFL) << 16) + (m_backupPeers[0] & 0x000000000000FFFFL);
			}
		}

		return ret;
	}

	// Methods
	/**
	 * Prints the locations
	 * @return String interpretation of locations
	 */
	@Override
	public String toString() {
		String ret;

		ret = "" + m_firstChunkIDORRangeID;
		if (null != m_backupPeers) {
			if (m_backupPeers.length == 3) {
				ret = "[" + m_backupPeers[0] + ", " + m_backupPeers[1] + ", " + m_backupPeers[2] + "]";
			} else if (m_backupPeers.length == 2) {
				ret = "[" + m_backupPeers[0] + ", " + m_backupPeers[1] + "]";
			} else {
				ret = "[" + m_backupPeers[0] + "]";
			}
		} else {
			ret = "no backup peers";
		}

		return ret;
	}
}