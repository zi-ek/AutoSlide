// 通用小工具：时间戳、HTML 转义、字节数格式化

/**
 * 统一的时间戳格式。
 * 原先这行 toLocaleString 在 8 处重复出现，任何一处改了时区就会和其它数据对不上。
 */
function nowCN() {
  return new Date().toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' });
}

/* HTML 转义，所有拼进页面的外部数据都必须过这一道 */
function esc(s) {
  return String(s || '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function fmtBytes(n) {
  const num = Number(n) || 0;
  if (num < 1024) return num + ' B';
  if (num < 1024 * 1024) return (num / 1024).toFixed(1) + ' KB';
  return (num / (1024 * 1024)).toFixed(1) + ' MB';
}

/**
 * 清洗设备上报的位置对象。
 *
 * 上报接口（/api/report、/api/report/network）无法鉴权，因此绝不整块存客户端传来的对象：
 * 逐项校验类型与范围，非法或缺失一律丢弃，经纬度不合法则整条判为无效返回 null。
 *
 * @param {*} raw 客户端上报的 location 段
 * @returns {{lat:number,lon:number,accuracy?:number,provider?:string,time?:number,address?:string}|null}
 */
function sanitizeLocation(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const lat = Number(raw.lat);
  const lon = Number(raw.lon);
  // 经纬度必须是有限数且在合法范围内，否则整条无效
  if (!Number.isFinite(lat) || lat < -90 || lat > 90) return null;
  if (!Number.isFinite(lon) || lon < -180 || lon > 180) return null;

  const out = { lat, lon };

  const acc = Number(raw.accuracy);
  if (Number.isFinite(acc) && acc >= 0) out.accuracy = acc;

  if (raw.provider) out.provider = String(raw.provider).slice(0, 20);

  const time = Number(raw.time);
  if (Number.isFinite(time) && time > 0) out.time = time;

  if (raw.address) out.address = String(raw.address).slice(0, 200);

  return out;
}

/**
 * WGS-84 → GCJ-02 坐标转换（俗称「火星坐标」偏移）。
 *
 * 安卓 GPS（LocationManager）给出的是国际标准 WGS-84；而高德/腾讯/谷歌中国等
 * 国内地图按法规一律用加密偏移后的 GCJ-02 展示。直接把 WGS-84 坐标丢给高德标点，
 * 会偏出 100~600 米，落到隔壁楼——这正是「坐标看着不准」的根因。
 *
 * 因此库里始终存原始 WGS-84（真值、与谷歌地球一致），只在拼高德链接时转成 GCJ-02，
 * 让标点落到正确位置。中国大陆以外不做偏移，原样返回。
 *
 * 算法为公开的经典实现（eviltransform / prcoords 同款常数）。
 *
 * @param {number} lat WGS-84 纬度
 * @param {number} lon WGS-84 经度
 * @returns {{lat:number, lon:number}} GCJ-02 坐标
 */
function wgs84ToGcj02(lat, lon) {
  // 中国大陆经纬度粗框：框外（含港澳台及境外）GCJ-02 与 WGS-84 一致，直接返回
  const outOfChina = !(lon > 73.66 && lon < 135.05 && lat > 3.86 && lat < 53.55);
  if (outOfChina) return { lat, lon };

  const A = 6378245.0; // 克拉索夫斯基椭球长半轴
  const EE = 0.00669342162296594323; // 第一偏心率平方

  const x = lon - 105.0;
  const y = lat - 35.0;

  let dLat =
    -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
  dLat += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0;
  dLat += ((20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin((y / 3.0) * Math.PI)) * 2.0) / 3.0;
  dLat +=
    ((160.0 * Math.sin((y / 12.0) * Math.PI) + 320 * Math.sin((y * Math.PI) / 30.0)) * 2.0) / 3.0;

  let dLon = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
  dLon += ((20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0) / 3.0;
  dLon += ((20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin((x / 3.0) * Math.PI)) * 2.0) / 3.0;
  dLon +=
    ((150.0 * Math.sin((x / 12.0) * Math.PI) + 300.0 * Math.sin((x / 30.0) * Math.PI)) * 2.0) / 3.0;

  const radLat = (lat / 180.0) * Math.PI;
  let magic = Math.sin(radLat);
  magic = 1 - EE * magic * magic;
  const sqrtMagic = Math.sqrt(magic);
  dLat = (dLat * 180.0) / (((A * (1 - EE)) / (magic * sqrtMagic)) * Math.PI);
  dLon = (dLon * 180.0) / ((A / sqrtMagic) * Math.cos(radLat) * Math.PI);

  return { lat: lat + dLat, lon: lon + dLon };
}

module.exports = { nowCN, esc, fmtBytes, sanitizeLocation, wgs84ToGcj02 };
