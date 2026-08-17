const fs = require('fs');
const path = require('path');
const ical = require('node-ical');
require('dotenv').config({ path: path.join(__dirname, '.env') });
require('dotenv').config({ path: path.join(__dirname, '.env.local') });

const { createClient } = require('@supabase/supabase-js');

const SUPABASE_URL =
  process.env.SUPABASE_URL ||
  'https://wrseisnaazmzyojnwpes.supabase.co';

const SUPABASE_KEY =
  process.env.SUPABASE_SERVICE_ROLE_KEY ||
  process.env.SUPABASE_KEY;

const GOOGLE_CALENDAR_ICS_URL =
  process.env.GOOGLE_CALENDAR_ICS_URL;

const KAKAO_REST_API_KEY =
  process.env.KAKAO_REST_API_KEY ||
  process.env.KAKAO_API_KEY;

const VEHICLE_ID = '3744141651867089';

const USER_UID =
  process.env.USER_UID ||
  'dwHcQZWCzBSzmJx8Q5qNkZjAH6d2';

const PROJECT_ID = 'teslacam-93532';

// ============================================================
// 중복 운행 판정 기준
// ============================================================

const DRIVING_DEDUP_WINDOW_MS = 5 * 60 * 1000;
const DRIVING_DEDUP_DISTANCE_KM = 0.5;
const MIN_DRIVING_DISTANCE_KM = 0.05;

// ============================================================
// GeoJSON
// ============================================================

let geojsonCache = null;

function loadGeoJson() {
  if (geojsonCache) return geojsonCache;

  try {
    const filePath = path.join(__dirname, 'hangjungdong.json');
    const parentFilePath = path.join(
      __dirname,
      '..',
      'hangjungdong.json'
    );

    let targetPath = null;

    if (fs.existsSync(filePath)) {
      targetPath = filePath;
    } else if (fs.existsSync(parentFilePath)) {
      targetPath = parentFilePath;
    }

    if (targetPath) {
      const raw = fs.readFileSync(targetPath, 'utf8');
      geojsonCache = JSON.parse(raw);
    }
  } catch (e) {
    console.error(
      'GeoJSON 로드 실패:',
      e.message
    );
  }

  return geojsonCache;
}

function isPointInPolygon(point, vs) {
  if (!vs || !Array.isArray(vs)) return false;

  const x = point[0];
  const y = point[1];

  let inside = false;

  for (
    let i = 0, j = vs.length - 1;
    i < vs.length;
    j = i++
  ) {
    const xi = vs[i][0];
    const yi = vs[i][1];

    const xj = vs[j][0];
    const yj = vs[j][1];

    const intersect =
      ((yi > y) !== (yj > y)) &&
      (
        x <
        ((xj - xi) * (y - yi)) /
          (yj - yi) +
          xi
      );

    if (intersect) {
      inside = !inside;
    }
  }

  return inside;
}

function getDongFromCoords(lat, lng) {
  const geojson = loadGeoJson();

  if (
    !lat ||
    !lng ||
    isNaN(lat) ||
    isNaN(lng) ||
    !geojson
  ) {
    return '';
  }

  const pt = [
    Number(lng),
    Number(lat)
  ];

  try {
    for (const feature of geojson.features || []) {
      const geom = feature.geometry;

      if (!geom) continue;

      const props = feature.properties || {};

      let fullAddr =
        props.adm_nm ||
        props.full_name ||
        '';

      if (!fullAddr) {
        const sido =
          props.sidonm ||
          props.sido ||
          '';

        const sgg =
          props.sggnm ||
          props.sgg ||
          '';

        const emd =
          props.emdnm ||
          props.emd ||
          '';

        fullAddr =
          `${sido} ${sgg} ${emd}`.trim();
      }

      if (
        geom.type === 'Polygon' &&
        geom.coordinates &&
        geom.coordinates[0]
      ) {
        if (
          isPointInPolygon(
            pt,
            geom.coordinates[0]
          )
        ) {
          return cleanAddressText(fullAddr);
        }
      } else if (
        geom.type === 'MultiPolygon' &&
        geom.coordinates
      ) {
        for (const poly of geom.coordinates) {
          if (
            poly &&
            poly[0] &&
            isPointInPolygon(
              pt,
              poly[0]
            )
          ) {
            return cleanAddressText(fullAddr);
          }
        }
      }
    }
  } catch (e) {
    console.error(
      '행정동 주소 변환 오류:',
      e.message
    );
  }

  return '';
}

function cleanAddressText(addr) {
  if (!addr) return '';

  return addr
    .replace(/^서울특별시\s*/, '')
    .replace(/^경기도\s*/, '')
    .replace(/고양시\s*/, '')
    .replace(/\s+/g, ' ')
    .trim();
}

// ============================================================
// Firestore 파싱
// ============================================================

function parseFirestoreValue(valObj) {
  if (!valObj) return null;

  if (valObj.stringValue !== undefined) {
    return valObj.stringValue;
  }

  if (valObj.integerValue !== undefined) {
    return Number(valObj.integerValue);
  }

  if (valObj.doubleValue !== undefined) {
    return Number(valObj.doubleValue);
  }

  if (valObj.booleanValue !== undefined) {
    return valObj.booleanValue;
  }

  if (valObj.timestampValue !== undefined) {
    return valObj.timestampValue;
  }

  if (valObj.nullValue !== undefined) {
    return null;
  }

  if (valObj.mapValue) {
    return parseFirestoreFields(
      valObj.mapValue.fields || {}
    );
  }

  if (valObj.arrayValue) {
    const list =
      valObj.arrayValue.values || [];

    return list.map(v =>
      parseFirestoreValue(v)
    );
  }

  return null;
}

function parseFirestoreFields(fields) {
  if (!fields) return {};

  const result = {};

  for (const key in fields) {
    result[key] =
      parseFirestoreValue(fields[key]);
  }

  return result;
}

// ============================================================
// 시간 파싱
// ============================================================

function parseDrivingTimeToMinutes(rawVal) {
  if (
    rawVal === undefined ||
    rawVal === null
  ) {
    return 0;
  }

  if (typeof rawVal === 'number') {
    if (!Number.isFinite(rawVal)) {
      return 0;
    }

    return Math.round(rawVal);
  }

  const str = String(rawVal).trim();

  let hours = 0;
  let mins = 0;

  const hourMatch =
    str.match(/(\d+)\s*시간/);

  if (hourMatch) {
    hours =
      parseInt(
        hourMatch[1],
        10
      );
  }

  const minMatch =
    str.match(/(\d+)\s*분/);

  if (minMatch) {
    mins =
      parseInt(
        minMatch[1],
        10
      );
  }

  if (
    hourMatch ||
    minMatch
  ) {
    return hours * 60 + mins;
  }

  const numOnly =
    parseFloat(str);

  if (!isNaN(numOnly)) {
    return Math.round(numOnly);
  }

  return 0;
}

function safeToISOString(
  val,
  fallback = null
) {
  if (!val) return fallback;

  try {
    if (typeof val === 'number') {
      const d = new Date(val);

      if (!isNaN(d.getTime())) {
        return d.toISOString();
      }
    }

    if (typeof val === 'string') {
      const num = Number(val);

      if (
        !isNaN(num) &&
        val.trim() !== ''
      ) {
        const d = new Date(num);

        if (!isNaN(d.getTime())) {
          return d.toISOString();
        }
      }

      const d = new Date(val);

      if (!isNaN(d.getTime())) {
        return d.toISOString();
      }
    }
  } catch (e) {}

  return fallback;
}

// ============================================================
// 운행 시간 계산
//
// 가장 중요한 부분:
// Firestore의 duration 값보다
// start_time → end_time 차이를 우선 사용한다.
// ============================================================

function calculateDrivingDuration(
  startISO,
  endISO,
  rawDuration
) {
  if (
    startISO &&
    endISO
  ) {
    const startMs =
      new Date(startISO).getTime();

    const endMs =
      new Date(endISO).getTime();

    if (
      Number.isFinite(startMs) &&
      Number.isFinite(endMs) &&
      endMs > startMs
    ) {
      return Math.round(
        (endMs - startMs) / 60000
      );
    }
  }

  return parseDrivingTimeToMinutes(
    rawDuration
  );
}

// ============================================================
// Google ID Token
// ============================================================

async function getGoogleIdToken() {
  if (
    !process.env.GOOGLE_API_KEY ||
    !process.env.GOOGLE_REFRESH_TOKEN
  ) {
    return null;
  }

  try {
    const res = await fetch(
      `https://securetoken.googleapis.com/v1/token?key=${process.env.GOOGLE_API_KEY}`,
      {
        method: 'POST',
        headers: {
          'Content-Type':
            'application/x-www-form-urlencoded'
        },
        body: new URLSearchParams({
          grant_type:
            'refresh_token',
          refresh_token:
            process.env.GOOGLE_REFRESH_TOKEN
        })
      }
    );

    const data =
      await res.json();

    if (
      !res.ok ||
      !data.id_token
    ) {
      console.error(
        '[Google Auth] 토큰 갱신 실패:',
        data.error,
        '-',
        data.error_description
      );

      return null;
    }

    return data.id_token;
  } catch (e) {
    console.error(
      '[Google Auth] 예외:',
      e.message
    );

    return null;
  }
}

// ============================================================
// Supabase 기존 ID 조회
// ============================================================

async function fetchExistingIds(
  supabase,
  tableName
) {
  const ids = new Set();

  const PAGE = 1000;
  let from = 0;

  try {
    while (true) {
      const {
        data,
        error
      } = await supabase
        .from(tableName)
        .select('id')
        .range(
          from,
          from + PAGE - 1
        );

      if (
        error ||
        !data ||
        data.length === 0
      ) {
        break;
      }

      data.forEach(row => {
        if (row.id) {
          ids.add(row.id);
        }
      });

      if (data.length < PAGE) {
        break;
      }

      from += PAGE;
    }
  } catch (e) {
    console.warn(
      `[${tableName}] 기존 ID 조회 실패:`,
      e.message
    );
  }

  return ids;
}

// ============================================================
// 기존 driving 데이터
// ============================================================

async function fetchExistingDrivingRows(
  supabase
) {
  const rows = [];

  const PAGE = 1000;
  let from = 0;

  try {
    while (true) {
      const {
        data,
        error
      } = await supabase
        .from('driving')
        .select(
          [
            'id',
            'vehicle_id',
            'distance_km',
            'move_km',
            'start_time',
            'end_time',
            'duration_min',
            'driving_time'
          ].join(',')
        )
        .range(
          from,
          from + PAGE - 1
        );

      if (error) {
        console.warn(
          '기존 driving 조회 실패:',
          error.message
        );
        break;
      }

      if (
        !data ||
        data.length === 0
      ) {
        break;
      }

      rows.push(...data);

      if (data.length < PAGE) {
        break;
      }

      from += PAGE;
    }
  } catch (e) {
    console.warn(
      '기존 driving 조회 예외:',
      e.message
    );
  }

  return rows;
}

// ============================================================
// driving 중복 검사
//
// 조건:
// 1. 같은 차량
// 2. start_time 차이 <= 5분
// 3. 거리 차이 < 0.5km
//
// 텍스트(start_dong 등)는 비교하지 않는다.
// ============================================================

function findDrivingDuplicate(
  existingRows,
  newRecord
) {
  if (
    !newRecord ||
    !newRecord.start_time
  ) {
    return null;
  }

  const newStartMs =
    new Date(
      newRecord.start_time
    ).getTime();

  if (!Number.isFinite(newStartMs)) {
    return null;
  }

  const newDistance =
    Number(
      newRecord.distance_km ??
      newRecord.move_km ??
      0
    );

  for (const row of existingRows) {
    if (
      row.vehicle_id &&
      newRecord.vehicle_id &&
      String(row.vehicle_id) !==
        String(newRecord.vehicle_id)
    ) {
      continue;
    }

    if (!row.start_time) {
      continue;
    }

    const existingStartMs =
      new Date(
        row.start_time
      ).getTime();

    if (
      !Number.isFinite(
        existingStartMs
      )
    ) {
      continue;
    }

    const timeDiff =
      Math.abs(
        existingStartMs -
        newStartMs
      );

    if (
      timeDiff >
      DRIVING_DEDUP_WINDOW_MS
    ) {
      continue;
    }

    const existingDistance =
      Number(
        row.distance_km ??
        row.move_km ??
        0
      );

    const distanceDiff =
      Math.abs(
        existingDistance -
        newDistance
      );

    if (
      distanceDiff <
      DRIVING_DEDUP_DISTANCE_KM
    ) {
      return row;
    }
  }

  return null;
}

// ============================================================
// batch 내부 driving 중복 검사
// ============================================================

function findBatchDrivingDuplicate(
  batch,
  newRecord
) {
  if (
    !newRecord ||
    !newRecord.start_time
  ) {
    return -1;
  }

  const newStartMs =
    new Date(
      newRecord.start_time
    ).getTime();

  if (!Number.isFinite(newStartMs)) {
    return -1;
  }

  const newDistance =
    Number(
      newRecord.distance_km ??
      newRecord.move_km ??
      0
    );

  return batch.findIndex(item => {
    if (!item.start_time) {
      return false;
    }

    const itemStartMs =
      new Date(
        item.start_time
      ).getTime();

    if (
      !Number.isFinite(
        itemStartMs
      )
    ) {
      return false;
    }

    const timeDiff =
      Math.abs(
        itemStartMs -
        newStartMs
      );

    if (
      timeDiff >
      DRIVING_DEDUP_WINDOW_MS
    ) {
      return false;
    }

    const itemDistance =
      Number(
        item.distance_km ??
        item.move_km ??
        0
      );

    const distanceDiff =
      Math.abs(
        itemDistance -
        newDistance
      );

    return (
      distanceDiff <
      DRIVING_DEDUP_DISTANCE_KM
    );
  });
}

// ============================================================
// Supabase upsert
// ============================================================

async function upsertWithRetry(
  supabase,
  tableName,
  records,
  options = {},
  maxRetries = 3
) {
  const CHUNK_SIZE = 30;

  let totalSaved = 0;

  for (
    let i = 0;
    i < records.length;
    i += CHUNK_SIZE
  ) {
    const chunk =
      records.slice(
        i,
        i + CHUNK_SIZE
      );

    let attempt = 0;
    let success = false;

    while (
      attempt < maxRetries &&
      !success
    ) {
      attempt++;

      try {
        let {
          data: inserted,
          error
        } = await supabase
          .from(tableName)
          .upsert(
            chunk,
            options
          )
          .select();

        if (
          error &&
          (
            error.message?.includes(
              'location_list'
            ) ||
            error.code === '22P02'
          )
        ) {
          const fallbackChunk =
            chunk.map(item => ({
              ...item,
              location_list:
                typeof item.location_list ===
                'object'
                  ? JSON.stringify(
                      item.location_list
                    )
                  : item.location_list
            }));

          const retry =
            await supabase
              .from(tableName)
              .upsert(
                fallbackChunk,
                options
              )
              .select();

          error = retry.error;
          inserted = retry.data;
        }

        if (error) {
          throw error;
        }

        totalSaved +=
          inserted?.length ||
          chunk.length;

        success = true;
      } catch (err) {
        console.warn(
          `⚠️ [${tableName}] 저장 시도 ${attempt}/${maxRetries} 실패:`,
          err.message
        );

        if (
          attempt >= maxRetries
        ) {
          console.error(
            `❌ Supabase ${tableName} 저장 최종 실패`
          );
        } else {
          await new Promise(
            resolve =>
              setTimeout(
                resolve,
                2000
              )
          );
        }
      }
    }
  }

  return totalSaved;
}

// ============================================================
// PSI 변환
// ============================================================

function convertToPsi(val) {
  if (!val || isNaN(val)) {
    return 0;
  }

  const num = Number(val);

  if (
    num > 0 &&
    num < 10
  ) {
    return Math.round(
      num * 14.5038
    );
  }

  return Math.round(num);
}

// ============================================================
// 여기까지가 1부
// ============================================================
      // ============================================================
      // 🔥 중복 운행 제거
      // ============================================================
      //
      // 같은 운행이 Firestore에 여러 번 기록되는 경우를 방지합니다.
      //
      // 기준:
      //   1. start_time 차이 <= 5분
      //   2. distance_km 차이 < 0.5km
      //
      // 단순히 created_at을 비교하면 동기화 시각이 달라져
      // 정상적인 운행도 중복으로 판단할 수 있으므로
      // 반드시 실제 운행 start_time을 기준으로 합니다.
      //
      const DEDUP_WINDOW_MS = 5 * 60 * 1000;
      const DEDUP_DISTANCE_KM = 0.5;

      const newStartMs = newRecord.start_time
        ? new Date(newRecord.start_time).getTime()
        : null;

      let duplicateIndex = -1;

      if (newStartMs && !isNaN(newStartMs)) {
        duplicateIndex = drivingBatch.findIndex(existing => {
          if (!existing.start_time) return false;

          const existingStartMs =
            new Date(existing.start_time).getTime();

          if (isNaN(existingStartMs)) return false;

          const timeDiff =
            Math.abs(existingStartMs - newStartMs);

          const distanceDiff =
            Math.abs(
              Number(existing.distance_km || existing.move_km || 0) -
              Number(newRecord.distance_km || newRecord.move_km || 0)
            );

          return (
            timeDiff <= DEDUP_WINDOW_MS &&
            distanceDiff < DEDUP_DISTANCE_KM
          );
        });
      }

      if (duplicateIndex !== -1) {
        const existing = drivingBatch[duplicateIndex];

        // 같은 운행이면 더 완전한 데이터를 우선합니다.
        const existingDuration =
          Number(existing.duration_min || existing.driving_time || 0);

        const newDuration =
          Number(newRecord.duration_min || newRecord.driving_time || 0);

        const existingLocationCount =
          Array.isArray(existing.location_list)
            ? existing.location_list.length
            : 0;

        const newLocationCount =
          Array.isArray(newRecord.location_list)
            ? newRecord.location_list.length
            : 0;

        const shouldReplace =
          newDuration > existingDuration ||
          (
            newDuration === existingDuration &&
            newLocationCount > existingLocationCount
          );

        if (shouldReplace) {
          drivingBatch[duplicateIndex] = newRecord;
          console.log(
            `🔄 [driving] 중복 운행 교체: ` +
            `${existing.id} -> ${newRecord.id} ` +
            `(시간 ${newDuration}분, 위치 ${newLocationCount}개)`
          );
        } else {
          console.log(
            `⏭️ [driving] 중복 운행 스킵: ${newRecord.id}`
          );
        }

        drivingDedupSkipped++;
        continue;
      }

      drivingBatch.push(newRecord);
    }

    // ============================================================
    // driving 저장
    // ============================================================
    if (drivingBatch.length > 0) {
      summary.driving += await upsertWithRetry(
        supabase,
        'driving',
        drivingBatch,
        {
          onConflict: 'id',
          ignoreDuplicates: false
        }
      );
    }

    console.log(
      `📌 [driving] 신규 저장 ${summary.driving}건, ` +
      `기존 스킵 ${drivingSkipped}건, ` +
      `중복/0km차단 ${drivingDedupSkipped}건`
    );

    // ============================================================
    // 🔥 기존 driving 데이터의 시간 보정
    // ============================================================
    //
    // 기존 DB에 duration_min / driving_time이 0 또는 NULL인
    // 데이터가 이미 존재할 수 있습니다.
    //
    // start_time / end_time이 정상적으로 존재하면
    // 실제 시간 차이를 계산하여 보정합니다.
    //
    // 단, 여기서는 SQL RPC를 직접 만들지 않고
    // Supabase RPC가 존재하는 경우에만 실행합니다.
    //
    try {
      const { error: durationError } = await supabase.rpc(
        'fix_driving_duration'
      );

      if (durationError) {
        console.log(
          'ℹ️ [driving] fix_driving_duration RPC 미사용:',
          durationError.message
        );
      }
    } catch (e) {
      console.log(
        'ℹ️ [driving] 기존 duration 보정 RPC 건너뜀:',
        e.message
      );
    }

    // ============================================================
    // ========== charging ==========
    // ============================================================

    const existingChargingIds =
      await fetchExistingIds(supabase, 'charging');

    const chargingBaseUrl =
      `https://firestore.googleapis.com/v1/projects/${PROJECT_ID}` +
      `/databases/(default)/documents/vehicle/${vehicleId}/charging`;

    const chargingDocs =
      await fetchAllFirestoreDocs(chargingBaseUrl, headers);

    const chargingBatch = [];

    let chargingSkipped = 0;
    let chargingDedupSkipped = 0;

    if (chargingDocs.length > 0) {
      console.log(
        '🔍 [DEBUG] Charging sample fields:',
        JSON.stringify(
          chargingDocs[0].fields,
          null,
          2
        ).substring(0, 600)
      );
    }

    for (const doc of chargingDocs) {
      const fields = doc.fields || {};
      const p = parseFirestoreFields(fields);

      const docId = doc.name?.split('/').pop();

      if (!docId) continue;

      if (existingChargingIds.has(docId)) {
        chargingSkipped++;
        continue;
      }

      // ----------------------------------------------------------
      // 날짜
      // ----------------------------------------------------------

      let dateISO = null;

      if (p.date) {
        dateISO = safeToISOString(
          p.date,
          null
        );
      }

      if (!dateISO) {
        dateISO = safeToISOString(
          p.created_at || p.timestamp,
          doc.createTime ||
          new Date().toISOString()
        );
      }

      // ----------------------------------------------------------
      // 배터리
      // ----------------------------------------------------------

      const nowBattery =
        Number(
          p.nowBattery ??
          p.now_battery ??
          p.battery_level ??
          p.battery ??
          0
        );

      const addedBatteryLevel =
        Number(
          p.addedBatteryLevel ??
          p.added_battery_level ??
          p.added_battery ??
          0
        );

      let startBattery =
        Number(
          p.startBattery ??
          p.start_battery ??
          0
        );

      if (
        startBattery === 0 &&
        nowBattery > 0 &&
        addedBatteryLevel > 0
      ) {
        startBattery =
          Math.max(
            0,
            nowBattery - addedBatteryLevel
          );
      }

      // ----------------------------------------------------------
      // 충전량
      // ----------------------------------------------------------

      const addedCharging =
        Number(
          p.addedCharging ??
          p.added_charging ??
          p.added_kwh ??
          p.kwh ??
          0
        );

      const addedBatteryRange =
        Number(
          p.addedBatteryRange ??
          p.added_battery_range ??
          p.added_range ??
          0
        );

      // ----------------------------------------------------------
      // 위치
      // ----------------------------------------------------------

      let locList =
        p.location_list ??
        p.location ??
        p.path ??
        [];

      if (typeof locList === 'string') {
        try {
          locList = JSON.parse(locList);
        } catch (e) {
          locList = [];
        }
      }

      if (!Array.isArray(locList)) {
        locList = [];
      }

      const newRecord = {
        id: docId,

        user_uid: USER_UID,

        vehicle_id: vehicleId,

        date: dateISO,

        now_battery:
          Math.round(nowBattery * 100) / 100,

        added_battery_level:
          Math.round(addedBatteryLevel * 100) / 100,

        start_battery:
          Math.round(startBattery * 100) / 100,

        added_charging:
          Math.round(addedCharging * 100) / 100,

        added_battery_range:
          Math.round(addedBatteryRange * 100) / 100,

        location_list: locList,

        raw_data: p,

        created_at:
          dateISO ||
          new Date().toISOString()
      };

      // ==========================================================
      // 🔥 charging 중복 검사
      // ==========================================================

      const DEDUP_WINDOW_MS =
        5 * 60 * 1000;

      const newDateMs =
        newRecord.date
          ? new Date(newRecord.date).getTime()
          : null;

      let duplicateIndex = -1;

      if (
        newDateMs &&
        !isNaN(newDateMs)
      ) {
        duplicateIndex =
          chargingBatch.findIndex(existing => {

            if (!existing.date) {
              return false;
            }

            const existingDateMs =
              new Date(existing.date).getTime();

            if (isNaN(existingDateMs)) {
              return false;
            }

            const timeDiff =
              Math.abs(
                existingDateMs - newDateMs
              );

            const batteryDiff =
              Math.abs(
                Number(
                  existing.added_battery_level || 0
                ) -
                Number(
                  newRecord.added_battery_level || 0
                )
              );

            return (
              timeDiff <= DEDUP_WINDOW_MS &&
              batteryDiff < 5
            );
          });
      }

      if (duplicateIndex !== -1) {
        const existing =
          chargingBatch[duplicateIndex];

        const existingCharging =
          Number(
            existing.added_charging || 0
          );

        const newCharging =
          Number(
            newRecord.added_charging || 0
          );

        if (newCharging > existingCharging) {
          chargingBatch[duplicateIndex] =
            newRecord;

          console.log(
            `🔄 [charging] 더 정확한 기록으로 교체: ` +
            `${existing.id} -> ${newRecord.id}`
          );
        } else {
          console.log(
            `⏭️ [charging] 중복 충전 기록 스킵: ${newRecord.id}`
          );
        }

        chargingDedupSkipped++;
        continue;
      }

      chargingBatch.push(newRecord);
    }

    // ============================================================
    // charging 저장
    // ============================================================

    if (chargingBatch.length > 0) {
      summary.charging +=
        await upsertWithRetry(
          supabase,
          'charging',
          chargingBatch,
          {
            onConflict: 'id'
          }
        );
    }

    console.log(
      `📌 [charging] 신규 저장 ${summary.charging}건, ` +
      `기존 스킵 ${chargingSkipped}건, ` +
      `중복처리 ${chargingDedupSkipped}건`
    );

    // ============================================================
    // 🔥 주행 외부온도 보정
    // ============================================================

    try { 
  await supabase.rpc('update_driving_outside_temp'); 
} catch (e) {}

// 🔥 driving 실제 시간 기반 duration 보정
try {
  const { data: fixedCount, error: durationError } =
    await supabase.rpc('fix_driving_duration');

  if (durationError) {
    console.error(
      '❌ [driving] duration 보정 실패:',
      durationError.message
    );
  } else {
    console.log(
      `🔧 [driving] duration 보정 완료: ${fixedCount || 0}건`
    );
  }
} catch (e) {
  console.error(
    '❌ [driving] duration RPC 실행 오류:',
    e.message
  );
}

// ========== 캘린더 동기화 =========
    if (GOOGLE_CALENDAR_ICS_URL) {

      console.log(
        '📅 구글 캘린더 동기화 시작...'
      );

      const { count } =
        await supabase
          .from('calendar_events')
          .select('*', {
            count: 'exact',
            head: true
          });

      const isInitialRun =
        count === 0;

      const filterStartDate =
        isInitialRun
          ? new Date('2025-08-01T00:00:00Z')
          : new Date(
              Date.now() -
              30 * 24 * 60 * 60 * 1000
            );

      console.log(
        `📅 필터 시작일: ${filterStartDate.toISOString()}`
      );

      // ----------------------------------------------------------
      // 기존 GPS 캐시
      // ----------------------------------------------------------

      const existingGpsMap =
        new Map();

      const {
        data: existingRows
      } =
        await supabase
          .from('calendar_events')
          .select(
            'uid, location, gps_lat, gps_lng'
          )
          .not(
            'gps_lat',
            'is',
            null
          );

      (existingRows || [])
        .forEach(row => {

          existingGpsMap.set(
            row.uid,
            {
              location: row.location,
              lat: row.gps_lat,
              lng: row.gps_lng
            }
          );

        });

      // ----------------------------------------------------------
      // ICS
      // ----------------------------------------------------------

      const events =
        await ical.async.fromURL(
          GOOGLE_CALENDAR_ICS_URL
        );

      const calendarBatch = [];

      let totalEvents = 0;
      let skippedNoKorean = 0;
      let geocodeCount = 0;

      for (const key in events) {

        const ev = events[key];

        if (ev.type !== 'VEVENT') {
          continue;
        }

        const eventStart =
          ev.start
            ? new Date(ev.start)
            : null;

        if (
          !eventStart ||
          eventStart < filterStartDate
        ) {
          continue;
        }

        const summary =
          ev.summary || '';

        // 한글 없는 일정 제외
        if (!/[가-힣]/.test(summary)) {

          console.log(
            `⏭️ 한글 없음, 스킵: "${summary.substring(0, 30)}"`
          );

          skippedNoKorean++;

          continue;
        }

        totalEvents++;

        const location =
          ev.location || '';

        let gpsLat = null;
        let gpsLng = null;

        const uid =
          ev.uid ||
          `event_${Date.now()}_${Math.random()}`;

        const cached =
          existingGpsMap.get(uid);

        // --------------------------------------------------------
        // 기존 GPS 사용
        // --------------------------------------------------------

        if (
          cached &&
          cached.location === location &&
          cached.lat &&
          cached.lng
        ) {

          gpsLat = cached.lat;
          gpsLng = cached.lng;

        } else {

          // ------------------------------------------------------
          // 위치 문자열에서 GPS 직접 추출
          // ------------------------------------------------------

          const parsed =
            extractGpsFromLocation(
              location
            );

          if (
            parsed.lat &&
            parsed.lng
          ) {

            gpsLat = parsed.lat;
            gpsLng = parsed.lng;

          } else if (
            KAKAO_REST_API_KEY &&
            location.length > 3
          ) {

            console.log(
              `🔍 Geocoding: "${location.substring(0, 40)}..."`
            );

            const geocoded =
              await geocodeAddressKakao(
                location
              );

            if (
              geocoded.lat &&
              geocoded.lng
            ) {

              gpsLat = geocoded.lat;
              gpsLng = geocoded.lng;

              geocodeCount++;

              console.log(
                `✅ Geocoding 성공: (${gpsLat}, ${gpsLng})`
              );
            }

            await new Promise(
              r => setTimeout(r, 300)
            );
          }
        }

        calendarBatch.push({

          uid,

          summary,

          description:
            ev.description || '',

          location,

          start_time:
            ev.start
              ? new Date(ev.start).toISOString()
              : null,

          end_time:
            ev.end
              ? new Date(ev.end).toISOString()
              : null,

          updated_at:
            new Date().toISOString(),

          gps_lat: gpsLat,

          gps_lng: gpsLng
        });
      }

      console.log(
        `📊 캘린더 통계: ` +
        `총 ${totalEvents}개, ` +
        `한글 없어 스킵 ${skippedNoKorean}개, ` +
        `Geocoding 성공 ${geocodeCount}개`
      );

      if (calendarBatch.length > 0) {

        summary.calendar =
          await upsertWithRetry(
            supabase,
            'calendar_events',
            calendarBatch,
            {
              onConflict: 'uid'
            }
          );

        console.log(
          `✅ 캘린더 ${summary.calendar}개 저장`
        );
      }
    }

    // ============================================================
    // 최종 결과
    // ============================================================

    console.log(
      '============================================'
    );

    console.log(
      '✅ Tesla & Calendar 데이터 동기화 완료'
    );

    console.log(
      `🚗 vehicle: ${summary.vehicle}`
    );

    console.log(
      `🛣️ driving: ${summary.driving}`
    );

    console.log(
      `🔋 charging: ${summary.charging}`
    );

    console.log(
      `📅 calendar: ${summary.calendar}`
    );

    console.log(
      '============================================'
    );

    return res.status(200).json({

      success: true,

      message:
        '동기화 완료',

      summary,

      debug: debugInfo

    });

  } catch (err) {

    console.error(
      '❌ Sync Error:',
      err.message
    );

    console.error(err.stack);

    return res.status(500).json({

      success: false,

      error: err.message

    });
  }
}

module.exports = handler;
