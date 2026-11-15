'use strict';
// Sparse 3.4 GiB synthetic fixture: only two 4 MiB pieces contain data.
// This exercises actual BitTorrent metadata, concurrent distant byte ranges,
// and the HTTP cache without allocating or downloading a multi-gigabyte file.
var assert = require('assert'), crypto = require('crypto'), net = require('net'), http = require('http');
var Wire = require('peer-wire-protocol'), bncode = require('bncode'), runtime = require('./media-http.cjs');
var MiB = 1024 * 1024, pieceLength = 16 * MiB, pieceCount = 218, middleIndex = Math.floor(pieceCount / 2), total = pieceLength * pieceCount;
var head = new Buffer(pieceLength), middle = new Buffer(pieceLength), tail = new Buffer(pieceLength), hashes = new Buffer(pieceCount * 20);
head.fill(17); middle.fill(29); tail.fill(41); hashes.fill(0);
crypto.createHash('sha1').update(head).digest().copy(hashes, 0);
crypto.createHash('sha1').update(middle).digest().copy(hashes, middleIndex * 20);
crypto.createHash('sha1').update(tail).digest().copy(hashes, (pieceCount - 1) * 20);
var metadata = bncode.encode({ name: 'synthetic-video.mkv', length: total,
  'piece length': pieceLength, pieces: hashes });
var hash = crypto.createHash('sha1').update(metadata).digest('hex');
var secret = new Buffer(32); secret.fill(12); secret = secret.toString('hex');
var seed, service, port, sockets = [], mediaPieces = Object.create(null), firstRequestByPiece = Object.create(null), finished = false;
var watchdog = setTimeout(function () { finish(new Error('Large media regression watchdog')); }, 30000);
function finish(error) {
  if (finished) return;
  finished = true; clearTimeout(watchdog);
  sockets.forEach(function (socket) { socket.destroy(); });
  function done() {
    if (error) { console.error(error.stack || error); process.exitCode = 1; }
    else console.log('3.4 GiB sparse metadata / concurrent distant AVPlay ranges / private stats: PASS on ' + process.version);
  }
  if (service) service.stop(function () { if (seed) seed.close(done); else done(); });
  else if (seed) seed.close(done);
  else done();
}
function request(path, method, data, headers) {
  return new Promise(function (resolve, reject) {
    var body = data === undefined ? null : JSON.stringify(data);
    var reqHeaders = { Host: '127.0.0.1:' + port };
    Object.keys(headers || {}).forEach(function (key) { reqHeaders[key] = headers[key]; });
    if (body !== null) {
      reqHeaders['Content-Length'] = Buffer.byteLength(body);
      reqHeaders['Content-Type'] = 'application/json';
    }
    var req = http.request({host:'127.0.0.1',port:port,path:path,method:method||'GET',headers:reqHeaders},
      function (res) {
        var chunks = [];
        res.on('data', function (buf) { chunks.push(buf); });
        res.on('error', reject);
        res.on('end', function () { resolve({ status: res.statusCode, body: Buffer.concat(chunks), headers: res.headers }); });
      });
    req.on('error', reject);
    req.setTimeout(12000, function () { req.abort(); reject(new Error('Request timeout: ' + path)); });
    req.end(body);
  });
}
function stats(authorized) {
  return request('/' + hash + '/stats', 'GET', undefined,
    authorized ? { 'X-Starlit-Token': secret } : {});
}
function getRange(start, end, expected) {
  return request('/' + hash + '/0?ticket=' + ticket, 'GET', undefined,
    { Range: 'bytes=' + start + '-' + end }).then(function (res) {
    assert.equal(res.status, 206, res.body.toString());
    assert.equal(res.headers['content-range'], 'bytes ' + start + '-' + end + '/' + total);
    assert.equal(res.headers['content-length'], String(expected.length));
    assert(res.body.equals(expected), 'Distant media range bytes differ');
  });
}
function delay(ms) { return new Promise(function (resolve) { setTimeout(resolve, ms); }); }
var ticket = '', seededRequests = 0;
seed = net.createServer(function (socket) {
  sockets.push(socket);
  var wire = new Wire();
  socket.pipe(wire).pipe(socket);
  socket.on('error', function () {});
  wire.on('error', function (error) { finish(error); });
  wire.on('handshake', function (peerHash) {
    if (peerHash.toString('hex') !== hash) return finish(new Error('Unexpected torrent hash'));
    wire.handshake(hash, '-STSEED-123456789012', { extended: true });
    var bits = new Buffer(Math.ceil(pieceCount / 8)); bits.fill(0);
    bits[0] |= 128;
    bits[middleIndex >> 3] |= 128 >> (middleIndex % 8);
    bits[(pieceCount - 1) >> 3] |= 128 >> ((pieceCount - 1) % 8);
    wire.bitfield({ buffer: bits }); wire.unchoke();
  });
  wire.on('interested', function () { wire.unchoke(); });
  wire.on('extended', function (id, payload) {
    try {
      var msg = bncode.decode(payload);
      if (id === 0) wire.extended(0, { m: { ut_metadata: 1 }, metadata_size: metadata.length });
      else if (id === 1 && msg.msg_type === 0) {
        setTimeout(function () {
          wire.extended(1, Buffer.concat([
            bncode.encode({msg_type: 1, piece: msg.piece}),
            metadata.slice(msg.piece * 16384, (msg.piece + 1) * 16384)
          ]));
        }, 180);
      }
    } catch (error) { finish(error); }
  });
  wire.on('request', function (index, offset, length, cb) {
    seededRequests++;
    if (index !== 0 && index !== middleIndex && index !== pieceCount - 1) return finish(new Error('Unexpected piece ' + index));
    mediaPieces[index] = true;
    var data = index === 0 ? head : index === middleIndex ? middle : tail;
    // Keep one block missing per active piece briefly so stats can observe
    // the third AVPlay range waiting instead of all downloads finishing first.
    if (!firstRequestByPiece[index]) {
      firstRequestByPiece[index] = true;
      return setTimeout(function () { cb(null, data.slice(offset, offset + length)); }, 500);
    }
    cb(null, data.slice(offset, offset + length));
  });
});
seed.listen(0, '127.0.0.1', function () {
  var peerPort = seed.address().port;
  service = runtime.start({
    port: 0, controlToken: secret, dht: false, allowPrivatePeers: true,
    metadataTimeout: 12000,
    onEngine: function (engine) { engine.connect('127.0.0.1:' + peerPort); }
  }, function () {
    port = service.server.address().port;
    var creating = request('/' + hash + '/create', 'POST',
      { torrent: {infoHash:hash}, fileIdx:0, peerSearch: { sources:[] } },
      {'X-Starlit-Token':secret});
    delay(30).then(function () {
      return Promise.all([stats(false), stats(true)]);
    }).then(function (reports) {
      assert.equal(reports[0].status, 403, 'Metadata diagnostics must remain private');
      assert.equal(reports[1].status, 200, 'Metadata diagnostics must be accessible while loading');
      var pending = JSON.parse(reports[1].body.toString());
      assert.equal(pending.state, 'metadata');
      assert.equal(pending.pieceCount, null);
      assert(pending.metadataElapsedMs >= 0);
      return creating;
    }).then(function (response) {
      assert.equal(response.status, 200, response.body.toString());
      var result = JSON.parse(response.body.toString());
      assert.equal(result.files[0].length, total, 'Large video metadata lost precision');
      ticket = result.playbackTicket;
      assert(/^[a-f0-9]{64}$/.test(ticket));
      assert.equal(seededRequests, 0, 'Metadata resolution must not preload video');
      var ranges = Promise.all([
        getRange(0, 65535, head.slice(0, 65536)),
        getRange(middleIndex * pieceLength, middleIndex * pieceLength + 65535, middle.slice(0, 65536)),
        getRange(total - 65536, total - 1, tail.slice(pieceLength - 65536))
      ]);
      return delay(100).then(stats).then(function (report) {
        var state = JSON.parse(report.body.toString());
        assert.equal(state.pieceSelectionLimit, 2, 'Large pieces must use two active slots');
        assert.equal(state.activePieceSelections, 2, 'Two pieces should be actively selected');
        assert.equal(state.queuedPieceSelections, 1, 'Third AVPlay range must queue, not fail');
        return ranges;
      });
    }).then(function () {
      assert(mediaPieces[0] && mediaPieces[middleIndex] && mediaPieces[pieceCount - 1], 'Head, middle and tail must be downloaded');
      return stats(true);
    }).then(function (res) {
      assert.equal(res.status, 200);
      var ready = JSON.parse(res.body.toString());
      assert.equal(ready.state, 'ready');
      assert.equal(ready.pieceLengthBytes, pieceLength);
      assert.equal(ready.pieceCount, pieceCount);
      assert.equal(ready.pieceSelectionLimit, 2);
      assert.equal(ready.queuedPieceSelections, 0);
      assert(ready.lastPieceAgeMs >= 0);
      assert(ready.cache.bytes <= 48 * MiB, 'Completed-piece cache exceeded memory cap');
      finish();
    }).catch(finish);
  });
});