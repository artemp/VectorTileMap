/*
 * Copyright 2012 Hannes Janetzek
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.oscim.database.oscimap;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Map;

import org.oscim.core.BoundingBox;
import org.oscim.core.GeoPoint;
import org.oscim.core.Tag;
import org.oscim.core.Tile;
import org.oscim.database.IMapDatabase;
import org.oscim.database.IMapDatabaseCallback;
import org.oscim.database.MapInfo;
import org.oscim.database.OpenResult;
import org.oscim.database.QueryResult;
import org.oscim.generator.JobTile;

import android.os.Environment;
import android.os.SystemClock;
import android.util.Log;

/**
 * 
 *
 */
public class MapDatabase implements IMapDatabase {
	private static final String TAG = "MapDatabase";

	private static final MapInfo mMapInfo =
			new MapInfo(new BoundingBox(-180, -90, 180, 90),
					new Byte((byte) 4), new GeoPoint(53.11, 8.85),
					null, 0, 0, 0, "de", "comment", "author", null);

	private boolean mOpenFile = false;

	private static final boolean USE_CACHE = false;

	private static final String CACHE_DIRECTORY = "/Android/data/org.oscim.app/cache/";
	private static final String CACHE_FILE = "%d-%d-%d.tile";

	private static final String SERVER_ADDR = "city.informatik.uni-bremen.de";
	//private static final String URL = "/osci/map-live/";
	private static final String URL = "/osci/oscim/";

	private final static float REF_TILE_SIZE = 4096.0f;

	private int MAX_TILE_TAGS = 100;
	private Tag[] curTags = new Tag[MAX_TILE_TAGS];
	private int mCurTagCnt;

	private IMapDatabaseCallback mMapGenerator;
	private float mScaleFactor;
	private JobTile mTile;
	private FileOutputStream mCacheFile;

	private long mContentLenth;
	private InputStream mInputStream;

	private final boolean debug = false;

	@Override
	public QueryResult executeQuery(JobTile tile, IMapDatabaseCallback mapDatabaseCallback) {
		QueryResult result = QueryResult.SUCCESS;
		mCacheFile = null;

		mTile = tile;

		mMapGenerator = mapDatabaseCallback;

		// scale coordinates to tile size
		mScaleFactor = REF_TILE_SIZE / Tile.TILE_SIZE;

		File f = null;

		mBufferSize = 0;
		mBufferPos = 0;
		mReadPos = 0;

		if (USE_CACHE) {
			f = new File(cacheDir, String.format(CACHE_FILE,
					Integer.valueOf(tile.zoomLevel),
					Integer.valueOf(tile.tileX),
					Integer.valueOf(tile.tileY)));

			if (cacheRead(tile, f))
				return QueryResult.SUCCESS;
		}

		try {

			if (lwHttpSendRequest(tile)) {
				if (lwHttpReadHeader() >= 0) {

					cacheBegin(tile, f);
					decode();
				}
			} else {
				result = QueryResult.FAILED;
			}

		} catch (SocketException ex) {
			Log.d(TAG, "Socket exception: " + ex.getMessage());
			result = QueryResult.FAILED;
		} catch (SocketTimeoutException ex) {
			Log.d(TAG, "Socket Timeout exception: " + ex.getMessage());
			result = QueryResult.FAILED;
		} catch (UnknownHostException ex) {
			Log.d(TAG, "no network");
			result = QueryResult.FAILED;
		} catch (Exception ex) {
			ex.printStackTrace();
			result = QueryResult.FAILED;
		}

		mLastRequest = SystemClock.elapsedRealtime();

		cacheFinish(tile, f, result == QueryResult.SUCCESS);

		return result;
	}

	private static File cacheDir;

	@Override
	public String getMapProjection() {
		return null;
	}

	@Override
	public MapInfo getMapInfo() {
		return mMapInfo;
	}

	@Override
	public boolean isOpen() {
		return mOpenFile;
	}

	@Override
	public OpenResult open(Map<String, String> options) {

		if (USE_CACHE) {
			if (cacheDir == null) {
				String externalStorageDirectory = Environment
						.getExternalStorageDirectory()
						.getAbsolutePath();
				String cacheDirectoryPath = externalStorageDirectory + CACHE_DIRECTORY;
				cacheDir = createDirectory(cacheDirectoryPath);
			}
		}

		return OpenResult.SUCCESS;
	}

	@Override
	public void close() {
		mOpenFile = false;

		if (mSocket != null) {
			try {
				mSocket.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			mSocket = null;
		}

		if (USE_CACHE) {
			cacheDir = null;
		}
	}

	@Override
	public void cancel() {
	}

	private static File createDirectory(String pathName) {
		File file = new File(pathName);
		if (!file.exists() && !file.mkdirs()) {
			throw new IllegalArgumentException("could not create directory: " + file);
		} else if (!file.isDirectory()) {
			throw new IllegalArgumentException("not a directory: " + file);
		} else if (!file.canRead()) {
			throw new IllegalArgumentException("cannot read directory: " + file);
		} else if (!file.canWrite()) {
			throw new IllegalArgumentException("cannot write directory: " + file);
		}
		return file;
	}

	// /////////////// hand sewed tile protocol buffers decoder ///////////////
	// TODO write an own serialization format for structs and packed strings..

	private static final int BUFFER_SIZE = 65536;

	private final byte[] mReadBuffer = new byte[BUFFER_SIZE];

	// position in buffer
	private int mBufferPos;

	// bytes available in buffer
	private int mBufferSize;

	// overall bytes of content processed
	private int mBytesProcessed;

	// overall bytes of content read
	private int mReadPos;

	private static final int TAG_TILE_NUM_TAGS = 1;
	private static final int TAG_TILE_TAG_KEYS = 2;
	private static final int TAG_TILE_TAG_VALUES = 3;

	private static final int TAG_TILE_LINE = 11;
	private static final int TAG_TILE_POLY = 12;
	private static final int TAG_TILE_POINT = 13;
	// private static final int TAG_TILE_LABEL = 21;
	// private static final int TAG_TILE_WATER = 31;

	private static final int TAG_ELEM_NUM_INDICES = 1;
	private static final int TAG_ELEM_TAGS = 11;
	private static final int TAG_ELEM_INDEX = 12;
	private static final int TAG_ELEM_COORDS = 13;
	private static final int TAG_ELEM_LAYER = 21;
	// private static final int TAG_ELEM_PRIORITY = 31;

	private short[] mTmpKeys = new short[100];
	private short[] mIndices = new short[10];
	private Tag[] mTmpTags = new Tag[10];

	private int MAX_WAY_COORDS = 32768;
	private float[] mTmpCoords = new float[MAX_WAY_COORDS];

	private boolean decode() throws IOException {

		mCurTagCnt = 0;

		if (debug)
			Log.d(TAG, "Content length " + mContentLenth);

		mBytesProcessed = 0;
		int val;
		int numTags = 0;

		while (mBytesProcessed < mContentLenth && (val = decodeVarint32()) > 0) {
			// read tag and wire type
			int tag = (val >> 3);

			switch (tag) {
				case TAG_TILE_NUM_TAGS:
					numTags = decodeVarint32();
					if (numTags > curTags.length)
						curTags = new Tag[numTags];
					break;

				case TAG_TILE_TAG_KEYS:
					mTmpKeys = decodeShortArray(numTags, mTmpKeys);
					break;

				case TAG_TILE_TAG_VALUES:
					// this wastes one byte, as there is no packed string...
					decodeTileTags(mCurTagCnt++);
					break;

				case TAG_TILE_LINE:
				case TAG_TILE_POLY:
				case TAG_TILE_POINT:
					decodeTileElement(tag);
					break;

				default:
					Log.d(TAG, "invalid type for tile: " + tag);
					return false;
			}
		}
		return true;
	}

	private boolean decodeTileTags(int curTag) throws IOException {
		String tagString = decodeString();

		String key = Tags.keys[mTmpKeys[curTag]];
		Tag tag;

		if (key == Tag.TAG_KEY_NAME)
			tag = new Tag(key, tagString, false);
		else
			tag = new Tag(key, tagString, true);
		if (debug)
			Log.d(TAG, "add tag: " + curTag + " " + tag);
		curTags[curTag] = tag;

		return true;
	}

	private boolean decodeTileElement(int type) throws IOException {
		int bytes = decodeVarint32();

		int end = mBytesProcessed + bytes;
		int indexCnt = 1;
		int coordCnt = 0;
		int layer = 5;
		Tag[] tags = null;
		short[] index = null;

		boolean skip = false;
		boolean fail = false;
		if (debug)
			Log.d(TAG, "decode element: " + type);

		if (type == TAG_TILE_POINT)
			coordCnt = 2;

		while (mBytesProcessed < end) {
			// read tag and wire type
			int val = decodeVarint32();
			if (val == 0)
				break;

			int tag = (val >> 3);

			switch (tag) {
				case TAG_ELEM_TAGS:
					tags = decodeWayTags();
					break;

				case TAG_ELEM_NUM_INDICES:
					indexCnt = decodeVarint32();
					if (debug)
						Log.d(TAG, "elem num index: " + indexCnt);
					break;

				case TAG_ELEM_INDEX:
					index = decodeShortArray(indexCnt, mIndices);
					if (index != null) {
						mIndices = index;

						for (int i = 0; i < indexCnt; i++) {
							int len = index[i] * 2;
							coordCnt += len;
							index[i] = (short) len;
						}
						// set end marker
						if (indexCnt < index.length)
							index[indexCnt] = -1;
					}
					if (debug)
						Log.d(TAG, "elem index: " + coordCnt);
					break;

				case TAG_ELEM_COORDS:
					if (coordCnt == 0) {
						Log.d(TAG, "skipping way");
						skip = true;
					}
					int cnt = decodeWayCoordinates(skip, coordCnt);

					if (cnt != coordCnt) {
						Log.d(TAG, "X wrong number of coordintes");
						fail = true;
					}
					break;

				case TAG_ELEM_LAYER:
					layer = decodeVarint32();
					break;

				default:
					Log.d(TAG, "X invalid type for way: " + tag);
			}
		}

		if (fail || tags == null || indexCnt == 0) {
			Log.d(TAG, "failed reading way: bytes:" + bytes + " index:"
					+ (index == null ? "null" : index.toString()) + " tag:"
					+ (tags != null ? tags.toString() : "...") + " "
					+ indexCnt + " " + coordCnt);
			return false;
		}

		float[] coords = mTmpCoords;

		if (type == TAG_TILE_LINE)
			mMapGenerator.renderWay((byte) layer, tags, coords, index, false);
		else if (type == TAG_TILE_POLY)
			mMapGenerator.renderWay((byte) layer, tags, coords, index, true);
		else {
			if (debug)
				Log.d(TAG, "add poi " + coords[1] + " " + coords[0] + " " + tags[0]);
			mMapGenerator.renderPointOfInterest((byte) layer, tags, coords[1], coords[0]);
			// for (int i = 0; i < index[0]; i++)

		}

		return true;
	}

	private Tag[] decodeWayTags() throws IOException {
		int bytes = decodeVarint32();

		Tag[] tmp = mTmpTags;

		int cnt = 0;
		int end = mBytesProcessed + bytes;
		int max = mCurTagCnt;

		while (mBytesProcessed < end) {
			int tagNum = decodeVarint32();

			if (tagNum < 0) {
				Log.d(TAG, "NULL TAG: " + mTile + " invalid tag:" + tagNum + " " + cnt);
			} else {
				if (tagNum < Tags.MAX)
					tmp[cnt++] = Tags.tags[tagNum];
				else {
					tagNum -= Tags.LIMIT;

					if (tagNum >= 0 && tagNum < max) {
						// Log.d(TAG, "variable tag: " + curTags[tagNum]);
						tmp[cnt++] = curTags[tagNum];
					} else {
						Log.d(TAG, "NULL TAG: " + mTile + " could find tag:"
								+ tagNum + " " + cnt);
					}
				}
			}
		}

		if (cnt == 0) {
			Log.d(TAG, "got no TAG!");
		}

		Tag[] tags = new Tag[cnt];
		for (int i = 0; i < cnt; i++)
			tags[i] = tmp[i];

		return tags;
	}

	private int decodeWayCoordinates(boolean skip, int nodes) throws IOException {
		int bytes = decodeVarint32();

		readBuffer(bytes);

		if (skip) {
			mBufferPos += bytes;
			return nodes;
		}

		int pos = mBufferPos;
		int end = pos + bytes;
		float[] coords = mTmpCoords;
		byte[] buf = mReadBuffer;
		int cnt = 0;
		int result;

		int x, lastX = 0;
		int y, lastY = 0;
		boolean even = true;

		float scale = mScaleFactor;

		if (nodes * 2 > coords.length) {
			Log.d(TAG, "increase way coord buffer " + mTile + " to " + (nodes * 2));
			float[] tmp = new float[nodes * 2];
			mTmpCoords = coords = tmp;
		}

		// read repeated sint32
		while (pos < end) {

			if (buf[pos] >= 0) {
				result = buf[pos++];
			} else if (buf[pos + 1] >= 0) {
				result = (buf[pos] & 0x7f)
						| buf[pos + 1] << 7;
				pos += 2;
			} else if (buf[pos + 2] >= 0) {
				result = (buf[pos] & 0x7f)
						| (buf[pos + 1] & 0x7f) << 7
						| (buf[pos + 2]) << 14;
				pos += 3;
			} else if (buf[pos + 3] >= 0) {
				result = (buf[pos] & 0x7f)
						| (buf[pos + 1] & 0x7f) << 7
						| (buf[pos + 2] & 0x7f) << 14
						| (buf[pos + 3]) << 21;
				pos += 4;
			} else {
				result = (buf[pos] & 0x7f)
						| (buf[pos + 1] & 0x7f) << 7
						| (buf[pos + 2] & 0x7f) << 14
						| (buf[pos + 3] & 0x7f) << 21
						| (buf[pos + 4]) << 28;
				pos += 4;
				int i = 0;

				while (buf[pos++] < 0 && i < 10)
					i++;

				if (i == 10)
					throw new IOException("X malformed VarInt32");

			}
			if (even) {
				x = ((result >>> 1) ^ -(result & 1));
				lastX = lastX + x;
				coords[cnt++] = lastX / scale;
				even = false;
			} else {
				y = ((result >>> 1) ^ -(result & 1));
				lastY = lastY + y;
				coords[cnt++] = lastY / scale;
				even = true;
			}
		}

		mBufferPos = pos;
		mBytesProcessed += bytes;

		return cnt;
	}

	private int readBuffer(int size) throws IOException {
		int read = 0;

		if (mBufferPos + size < mBufferSize)
			return mBufferSize - mBufferPos;

		if (mReadPos == mContentLenth)
			return mBufferSize - mBufferPos;

		if (size > BUFFER_SIZE) {
			// FIXME throw exception for now, but frankly better
			// sanitize tile data on compilation. this should only
			// happen with strings or one ways coordinates are
			// larger than 64kb
			throw new IOException("X requested size too large " + mTile);
		}

		if (mBufferSize == mBufferPos) {
			mBufferPos = 0;
			mBufferSize = 0;
		} else if (mBufferPos + (size - mBufferSize) > BUFFER_SIZE) {
			Log.d(TAG, "wrap buffer" + (size - mBufferSize) + " " + mBufferPos);
			// copy bytes left to read to the beginning of buffer
			mBufferSize -= mBufferPos;
			System.arraycopy(mReadBuffer, mBufferPos, mReadBuffer, 0, mBufferSize);
			mBufferPos = 0;
		}

		int max = BUFFER_SIZE - mBufferSize;

		while ((mBufferSize - mBufferPos) < size && max > 0) {

			max = BUFFER_SIZE - mBufferSize;
			if (max > mContentLenth - mReadPos)
				max = (int) (mContentLenth - mReadPos);

			// read until requested size is available in buffer
			int len = mInputStream.read(mReadBuffer, mBufferSize, max);

			if (len < 0) {
				// finished reading, mark end
				mReadBuffer[mBufferSize] = 0;
				break;
			}

			read += len;
			mReadPos += len;

			if (mCacheFile != null)
				mCacheFile.write(mReadBuffer, mBufferSize, len);

			if (mReadPos == mContentLenth)
				break;

			mBufferSize += len;
		}
		return read;
	}

	private short[] decodeShortArray(int num, short[] array) throws IOException {
		int bytes = decodeVarint32();

		short[] index = array;
		if (index.length < num) {
			index = new short[num];
		}

		readBuffer(bytes);

		int cnt = 0;

		int pos = mBufferPos;
		int end = pos + bytes;
		byte[] buf = mReadBuffer;
		int result;

		while (pos < end) {

			if (buf[pos] >= 0) {
				result = buf[pos++];
			} else if (buf[pos + 1] >= 0) {
				result = (buf[pos] & 0x7f)
						| buf[pos + 1] << 7;
				pos += 2;
			} else if (buf[pos + 2] >= 0) {
				result = (buf[pos] & 0x7f)
						| (buf[pos + 1] & 0x7f) << 7
						| (buf[pos + 2]) << 14;
				pos += 3;
			} else if (buf[pos + 3] >= 0) {
				result = (buf[pos] & 0x7f)
						| (buf[pos + 1] & 0x7f) << 7
						| (buf[pos + 2] & 0x7f) << 14
						| (buf[pos + 3]) << 21;
				pos += 4;
			} else {
				result = (buf[pos] & 0x7f)
						| (buf[pos + 1] & 0x7f) << 7
						| (buf[pos + 2] & 0x7f) << 14
						| (buf[pos + 3] & 0x7f) << 21
						| (buf[pos + 4]) << 28;

				pos += 4;
				int i = 0;

				while (buf[pos++] < 0 && i < 10)
					i++;

				if (i == 10)
					throw new IOException("X malformed VarInt32");

			}

			index[cnt++] = (short) result;
		}

		mBufferPos = pos;
		mBytesProcessed += bytes;

		return index;
	}

	private int decodeVarint32() throws IOException {
		int pos = mBufferPos;

		if (pos + 10 > mBufferSize) {
			readBuffer(4096);
			pos = mBufferPos;
		}

		byte[] buf = mReadBuffer;

		if (buf[pos] >= 0) {
			mBufferPos += 1;
			mBytesProcessed += 1;
			return buf[pos];
		} else if (buf[pos + 1] >= 0) {
			mBufferPos += 2;
			mBytesProcessed += 2;
			return (buf[pos] & 0x7f)
					| (buf[pos + 1]) << 7;

		} else if (buf[pos + 2] >= 0) {
			mBufferPos += 3;
			mBytesProcessed += 3;
			return (buf[pos] & 0x7f)
					| (buf[pos + 1] & 0x7f) << 7
					| (buf[pos + 2]) << 14;
		} else if (buf[pos + 3] >= 0) {
			mBufferPos += 4;
			mBytesProcessed += 4;
			return (buf[pos] & 0x7f)
					| (buf[pos + 1] & 0x7f) << 7
					| (buf[pos + 2] & 0x7f) << 14
					| (buf[pos + 3]) << 21;
		}

		int result = (buf[pos] & 0x7f)
				| (buf[pos + 1] & 0x7f) << 7
				| (buf[pos + 2] & 0x7f) << 14
				| (buf[pos + 3] & 0x7f) << 21
				| (buf[pos + 4]) << 28;

		int read = 5;
		pos += 4;

		// 'Discard upper 32 bits' - the original comment.
		// havent found this in any document but the code provided by google.
		while (buf[pos++] < 0 && read < 10)
			read++;

		if (read == 10)
			throw new IOException("X malformed VarInt32");

		mBufferPos += read;
		mBytesProcessed += read;

		return result;
	}

	private String decodeString() throws IOException {
		final int size = decodeVarint32();
		readBuffer(size);
		final String result = new String(mReadBuffer, mBufferPos, size, "UTF-8");

		mBufferPos += size;
		mBytesProcessed += size;
		return result;

	}

	static int decodeInt(byte[] buffer, int offset) {
		return buffer[offset] << 24 | (buffer[offset + 1] & 0xff) << 16
				| (buffer[offset + 2] & 0xff) << 8
				| (buffer[offset + 3] & 0xff);
	}

	// ///////////////////////// Lightweight HttpClient ///////////////////////
	// should have written simple tcp server/client for this...

	private int mMaxReq = 0;
	private Socket mSocket;
	private OutputStream mCommandStream;
	private InputStream mResponseStream;
	private long mLastRequest = 0;
	private SocketAddress mSockAddr;

	private final static byte[] RESPONSE_HTTP_OK = "HTTP/1.1 200 OK".getBytes();
	private final static int RESPONSE_EXPECTED_LIVES = 100;
	private final static int RESPONSE_EXPECTED_TIMEOUT = 10000;

	private final static byte[] REQUEST_GET_START = ("GET " + URL).getBytes();
	private final static byte[] REQUEST_GET_END = (".osmtile HTTP/1.1\n" +
			"Host: " + SERVER_ADDR + "\n" +
			"Connection: Keep-Alive\n\n").getBytes();

	private byte[] mRequestBuffer;

	int lwHttpReadHeader() throws IOException {
		InputStream is = mResponseStream;

		byte[] buf = mReadBuffer;
		boolean first = true;
		int read = 0;
		int pos = 0;
		int end = 0;
		int len = 0;

		// header cannot be larger than BUFFER_SIZE for this to work
		for (; pos < read || (len = is.read(buf, read, BUFFER_SIZE - read)) >= 0; len = 0) {
			read += len;
			while (end < read && (buf[end] != '\n'))
				end++;

			if (buf[end] == '\n') {
				if (first) {
					// check only for OK
					first = false;
					if (!compareBytes(buf, pos, end, RESPONSE_HTTP_OK, 15))
						return -1;

					// for (int i = 0; i < 15 && pos + i < end; i++)
					// if (buf[pos + i] != RESPONSE_HTTP_OK[i])

				} else if (end - pos == 1) {
					// check empty line (header end)
					end += 1;
					break;
				}

				// String line = new String(buf, pos, end - pos - 1);
				// Log.d(TAG, ">" + line + "< " + resp_len);

				pos += (end - pos) + 1;
				end = pos;
			}
		}

		// check 4 bytes available..
		while ((read - end) < 4 && (len = is.read(buf, read, BUFFER_SIZE - read)) >= 0)
			read += len;

		if (read - len < 4)
			return -1;

		mContentLenth = decodeInt(buf, end);

		// buffer fill
		mBufferSize = read;
		// start of content
		mBufferPos = end + 4;
		// bytes of content already read into buffer
		mReadPos = read - mBufferPos;

		mInputStream = mResponseStream;

		return 1;
	}

	private boolean lwHttpSendRequest(Tile tile) throws IOException {
		if (mSockAddr == null) {
			mSockAddr = new InetSocketAddress(SERVER_ADDR, 80);
		}

		if (mSocket != null && ((mMaxReq-- <= 0)
				|| (SystemClock.elapsedRealtime() - mLastRequest
				> RESPONSE_EXPECTED_TIMEOUT))) {
			try {
				mSocket.close();
			} catch (IOException e) {

			}

			// Log.d(TAG, "not alive  - recreate connection " + mMaxReq);
			mSocket = null;
		}

		if (mSocket == null) {
			lwHttpConnect();
			// we know our server
			mMaxReq = RESPONSE_EXPECTED_LIVES;
			// Log.d(TAG, "create connection");
		} else {
			// should not be needed
			int avail = mResponseStream.available();
			if (avail > 0) {
				Log.d(TAG, "Consume left-over bytes: " + avail);
				mResponseStream.read(mReadBuffer, 0, avail);
			}
		}

		byte[] request = mRequestBuffer;
		int pos = REQUEST_GET_START.length;

		pos = writeInt(tile.zoomLevel, pos, request);
		request[pos++] = '/';
		pos = writeInt(tile.tileX, pos, request);
		request[pos++] = '/';
		pos = writeInt(tile.tileY, pos, request);

		int len = REQUEST_GET_END.length;
		System.arraycopy(REQUEST_GET_END, 0, request, pos, len);
		len += pos;

		// this does the same but with a few more allocations:
		// byte[] request = String.format(REQUEST,
		// Integer.valueOf(tile.zoomLevel),
		// Integer.valueOf(tile.tileX), Integer.valueOf(tile.tileY)).getBytes();

		try {
			mCommandStream.write(request, 0, len);
			mCommandStream.flush();
			return true;
		} catch (IOException e) {
			Log.d(TAG, "retry - recreate connection");
		}

		lwHttpConnect();

		mCommandStream.write(request, 0, len);
		mCommandStream.flush();

		return true;
	}

	private boolean lwHttpConnect() throws IOException {
		if (mRequestBuffer == null) {
			mRequestBuffer = new byte[1024];
			System.arraycopy(REQUEST_GET_START, 0,
					mRequestBuffer, 0, REQUEST_GET_START.length);
		}

		mSocket = new Socket();
		mSocket.connect(mSockAddr, 30000);
		mSocket.setTcpNoDelay(true);

		mCommandStream = new BufferedOutputStream(mSocket.getOutputStream());
		mResponseStream = mSocket.getInputStream();

		return true;
	}

	// write (positive) integer as char sequence to buffer
	private static int writeInt(int val, int pos, byte[] buf) {
		if (val == 0) {
			buf[pos] = '0';
			return pos + 1;
		}

		int i = 0;
		for (int n = val; n > 0; n = n / 10, i++)
			buf[pos + i] = (byte) ('0' + n % 10);

		// reverse bytes
		for (int j = pos, end = pos + i - 1, mid = pos + i / 2; j < mid; j++, end--) {
			byte tmp = buf[j];
			buf[j] = buf[end];
			buf[end] = tmp;
		}

		return pos + i;
	}

	private static boolean compareBytes(byte[] buffer, int position, int available,
			byte[] string, int length) {

		if (available - position < length)
			return false;

		for (int i = 0; i < length; i++)
			if (buffer[position + i] != string[i])
				return false;

		return true;
	}

	// ///////////////////////// Tile cache /////////////////////////////////

	private boolean cacheRead(Tile tile, File f) {
		if (f.exists() && f.length() > 0) {
			FileInputStream in;

			try {
				in = new FileInputStream(f);

				mContentLenth = f.length();
				Log.d(TAG, tile + " - using cache: " + mContentLenth);
				mInputStream = in;

				decode();
				in.close();

				return true;

			} catch (FileNotFoundException e) {
				e.printStackTrace();
			} catch (Exception ex) {
				ex.printStackTrace();
			}

			f.delete();
			return false;
		}

		return false;
	}

	private boolean cacheBegin(Tile tile, File f) {
		if (USE_CACHE) {
			try {
				Log.d(TAG, tile + " - writing cache");
				mCacheFile = new FileOutputStream(f);

				if (mReadPos > 0) {
					try {
						mCacheFile.write(mReadBuffer, mBufferPos,
								mBufferSize - mBufferPos);

					} catch (IOException e) {
						e.printStackTrace();
						mCacheFile = null;
						return false;
					}
				}
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				mCacheFile = null;
				return false;
			}
		}
		return true;
	}

	private void cacheFinish(Tile tile, File file, boolean success) {
		if (USE_CACHE) {
			if (success) {
				try {
					mCacheFile.flush();
					mCacheFile.close();
					Log.d(TAG, tile + " - cache written " + file.length());
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else {
				file.delete();
			}
		}
		mCacheFile = null;
	}
}
