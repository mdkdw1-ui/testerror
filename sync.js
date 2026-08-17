const { createClient } = require('@supabase/supabase-js');
require('dotenv').config();

const SUPABASE_URL =
  process.env.SUPABASE_URL ||
  'https://wrseisnaazmzyojnwpes.supabase.co';

const SUPABASE_KEY =
  process.env.SUPABASE_SERVICE_ROLE_KEY ||
  process.env.SUPABASE_KEY;

const PROJECT_ID = 'teslacam-93532';
const VEHICLE_ID = '3744141651867089';
const USER_UID =
  process.env.USER_UID ||
  'dwHcQZWCzBSzmJx8Q5qNkZjAH6d2';

const KAKAO_REST_API_KEY =
  process.env.KAKAO_REST_API_KEY ||
  process.env.KAKAO_API_KEY;

const TIMEOUT_MS = 10000;
const MAX_PAGES = 3;
const PAGE_SIZE = 50;


// ============================================================
// ⏱️ Timeout
// ============================================================

async function withTimeout(promise, ms = TIMEOUT_MS) {
  return Promise.race([
    promise,
    new Promise((_, reject) =>
      setTimeout(
        () => reject(new Error(`Timeout after ${ms}ms`)),
        ms
      )
    )
  ]);
}


// ============================================================
// 🔑 Google ID Token
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
          grant_type: 'refresh_token',
          refresh_token:
            process.env.GOOGLE_REFRESH_TOKEN
        })
      }
    );

    const data = await res.json();

    if (!res.ok || !data.id_token) {
      console.error(
        '[Google Auth] 토큰 갱신 실패:',
        data.error || 'unknown error'
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
// 📊 마지막 Supabase 기록 시간
// ============================================================

async function getLastRecordTimestamps(supabase) {
  const result = {
    driving: null,
    vehicle: null,
    charging: null
  };

  try {

    const { data: d } = await supabase
      .from('driving')
      .select('created_at')
      .order('created_at', {
        ascending: false
      })
      .limit(1);

    if (d?.length && d[0].created_at) {
      result.driving =
        new Date(d[0].created_at);
    }


    const { data: v } = await supabase
      .from('vehicle')
      .select('updated_at')
      .order('updated_at', {
        ascending: false
      })
      .limit(1);

    if (v?.length && v[0].updated_at) {
      result.vehicle =
        new Date(v[0].updated_at);
    }


    const { data: c } = await supabase
      .from('charging')
      .select('created_at')
      .order('created_at', {
        ascending: false
      })
      .limit(1);

    if (c?.length && c[0].created_at) {
      result.charging =
        new Date(c[0].created_at);
    }

  } catch (e) {
    console.warn(
      '[Supabase] 마지막 기록 시간 조회 실패:',
      e.message
    );
  }

  return result;
}


// ============================================================
// 🔥 Firestore 전체/부분 조회
// ============================================================

async function fetchFirestoreDocsSince(
  collection,
  token,
  sinceDate
) {

  let allDocs = [];
  let pageToken = null;
  let pageNum = 0;

  const sinceTimestamp =
    sinceDate
      ? sinceDate.getTime()
      : 0;

  while (pageNum < MAX_PAGES) {

    pageNum++;

    let url =
      `https://firestore.googleapis.com/v1/projects/` +
      `${PROJECT_ID}/databases/(default)/documents/` +
      `vehicle/${VEHICLE_ID}/${collection}`;

    const params =
      new URLSearchParams({
        pageSize: PAGE_SIZE
      });

    if (pageToken) {
      params.append(
        'pageToken',
        pageToken
      );
    }

    url += '?' + params.toString();

    const res = await fetch(
      url,
      {
        headers: {
          Authorization:
            `Bearer ${token}`
        }
      }
    );

    if (!res.ok) {

      const errBody =
        await res.text().catch(() => '');

      throw new Error(
        `Firestore error ${res.status}: ${errBody}`
      );
    }

    const data =
      await res.json();

    const docs =
      data.documents || [];

    console.log(
      `  [${collection}] 페이지 ${pageNum}: ` +
      `${docs.length}개 수신`
    );

    const filtered =
      docs.filter(doc => {

        const fields =
          doc.fields || {};

        const dateValue =
          fields.date?.integerValue ??
          fields.date?.doubleValue ??
          null;

        if (dateValue !== null) {

          const timestamp =
            Number(dateValue);

          return (
            !isNaN(timestamp) &&
            timestamp >= sinceTimestamp
          );
        }

        // date 필드가 없으면 일단 포함
        return true;
      });

    allDocs.push(...filtered);

    pageToken =
      data.nextPageToken || null;

    if (!pageToken) {
      break;
    }

    await new Promise(
      r => setTimeout(r, 50)
    );
  }

  return allDocs;
}


// ============================================================
// 🔄 Firestore 값 파싱
// ============================================================

function parseFirestoreValue(v) {

  if (!v) return null;

  if (v.stringValue !== undefined) {
    return v.stringValue;
  }

  if (v.integerValue !== undefined) {
    return Number(v.integerValue);
  }

  if (v.doubleValue !== undefined) {
    return Number(v.doubleValue);
  }

  if (v.booleanValue !== undefined) {
    return v.booleanValue;
  }

  if (v.timestampValue !== undefined) {
    return v.timestampValue;
  }

  if (v.nullValue !== undefined) {
    return null;
  }

  if (v.mapValue) {
    return parseFirestoreFields(
      v.mapValue.fields || {}
    );
  }

  if (v.arrayValue) {
    return (
      v.arrayValue.values || []
    ).map(item =>
      parseFirestoreValue(item)
    );
  }

  return null;
}


function parseFirestoreFields(fields) {

  if (!fields) return {};

  const result = {};

  for (const key in fields) {
    result[key] =
      parseFirestoreValue(
        fields[key]
      );
  }

  return result;
}


// ============================================================
// ⏱️ 날짜 안전 변환
// ============================================================

function safeToISOString(
  value,
  fallback = null
) {

  if (
    value === undefined ||
    value === null ||
    value === ''
  ) {
    return fallback;
  }

  try {

    if (
      typeof value === 'number' &&
      isFinite(value)
    ) {

      let ms = value;

      // Unix seconds
      if (Math.abs(ms) < 100000000000) {
        ms *= 1000;
      }

      const d = new Date(ms);

      if (!isNaN(d.getTime())) {
        return d.toISOString();
      }
    }


    if (typeof value === 'string') {

      const trimmed =
        value.trim();

      if (!trimmed) {
        return fallback;
      }

      // 숫자로 저장된 timestamp
      if (/^-?\d+(\.\d+)?$/.test(trimmed)) {

        let num =
          Number(trimmed);

        if (Math.abs(num) < 100000000000) {
          num *= 1000;
        }

        const d =
          new Date(num);

        if (!isNaN(d.getTime())) {
          return d.toISOString();
        }
      }

      const d =
        new Date(trimmed);

      if (!isNaN(d.getTime())) {
        return d.toISOString();
      }
    }

  } catch (e) {}

  return fallback;
}


// ============================================================
// ⏱️ 운행시간 문자열/숫자 파싱
// ============================================================

function parseDrivingTimeToMinutes(rawVal) {

  if (
    rawVal === undefined ||
    rawVal === null
  ) {
    return 0;
  }

  if (typeof rawVal === 'number') {

    if (!isFinite(rawVal)) {
      return 0;
    }

    return Math.round(rawVal);
  }

  const str =
    String(rawVal).trim();

  if (!str) return 0;

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

  if (hourMatch || minMatch) {
    return hours * 60 + mins;
  }

  const numOnly =
    parseFloat(str);

  if (!isNaN(numOnly)) {
    return Math.round(numOnly);
  }

  return 0;
}


// ============================================================
// 🔥 실제 start_time/end_time 기반 운행시간 계산
// ============================================================

function calculateDurationMinutes(
  startTime,
  endTime,
  fallback = 0
) {

  if (
    !startTime ||
    !endTime
  ) {
    return fallback;
  }

  const start =
    new Date(startTime).getTime();

  const end =
    new Date(endTime).getTime();

  if (
    !isFinite(start) ||
    !isFinite(end) ||
    end <= start
  ) {
    return fallback;
  }

  return Math.round(
    (end - start) / 60000
  );
}


// ============================================================
// 📦 Supabase Upsert
// ============================================================

async function upsertWithRetry(
  supabase,
  table,
  records,
  maxRetries = 2
) {

  const CHUNK = 20;
  let saved = 0;

  for (
    let i = 0;
    i < records.length;
    i += CHUNK
  ) {

    const chunk =
      records.slice(
        i,
        i + CHUNK
      );

    let attempt = 0;
    let ok = false;

    while (
      !ok &&
      attempt < maxRetries
    ) {

      attempt++;

      try {

        const { error } =
          await supabase
            .from(table)
            .upsert(
              chunk,
              {
                onConflict: 'id'
              }
            );

        if (error) {
          throw error;
        }

        saved +=
          chunk.length;

        ok = true;

      } catch (e) {

        console.warn(
          `⚠️ [${table}] 저장 실패 ` +
          `${attempt}/${maxRetries}: ` +
          `${e.message}`
        );

        if (
          attempt < maxRetries
        ) {
          await new Promise(
            r => setTimeout(
              r,
              1000
            )
          );
        }
      }
    }
  }

  return saved;
}


// ============================================================
// 📍 Kakao API
// ============================================================

async function callKakaoLocalApi(
  endpoint,
  query,
  apiKey
) {

  const encoded =
    encodeURIComponent(query);

  const url =
    `https://dapi.kakao.com/v2/local/search/` +
    `${endpoint}.json?query=${encoded}`;

  const res =
    await fetch(
      url,
      {
        headers: {
          Authorization:
            `KakaoAK ${apiKey}`
        }
      }
    );

  if (!res.ok) {
    return null;
  }

  const data =
    await res.json();

  if (
    data.documents &&
    data.documents.length > 0
  ) {

    const doc =
      data.documents[0];

    const lat =
      parseFloat(doc.y);

    const lng =
      parseFloat(doc.x);

    if (
      !isNaN(lat) &&
      !isNaN(lng) &&
      lat !== 0 &&
      lng !== 0
    ) {
      return {
        lat,
        lng
      };
    }
  }

  return null;
}


async function geocodeAddressKakao(
  rawLocation
) {

  if (
    !rawLocation ||
    typeof rawLocation !== 'string'
  ) {
    return {
      lat: null,
      lng: null
    };
  }

  const trimmed =
    rawLocation.trim();

  if (trimmed.length < 3) {
    return {
      lat: null,
      lng: null
    };
  }

  const apiKey =
    KAKAO_REST_API_KEY;

  if (!apiKey) {
    return {
      lat: null,
      lng: null
    };
  }

  const match =
    trimmed.match(
      /^(.*?)\(([^)]+)\)\s*$/
    );

  const placeName =
    match
      ? match[1].trim()
      : null;

  const addressPart =
    match
      ? match[2].trim()
      : null;

  try {

    if (addressPart) {

      const hit =
        await callKakaoLocalApi(
          'address',
          addressPart,
          apiKey
        );

      if (hit) return hit;

      await new Promise(
        r => setTimeout(r, 120)
      );
    }


    if (
      placeName &&
      placeName.length >= 2
    ) {

      const hit =
        await callKakaoLocalApi(
          'keyword',
          placeName,
          apiKey
        );

      if (hit) return hit;

      await new Promise(
        r => setTimeout(r, 120)
      );
    }


    if (addressPart) {

      const hit =
        await callKakaoLocalApi(
          'keyword',
          addressPart,
          apiKey
        );

      if (hit) return hit;

      await new Promise(
        r => setTimeout(r, 120)
      );
    }


    const hit =
      await callKakaoLocalApi(
        'keyword',
        trimmed,
        apiKey
      );

    if (hit) return hit;

  } catch (e) {}

  return {
    lat: null,
    lng: null
  };
}


function extractGpsFromLocation(
  location
) {

  if (
    !location ||
    typeof location !== 'string'
  ) {
    return {
      lat: null,
      lng: null
    };
  }

  let m =
    location.match(
      /(\d+\.\d+)[\s,]+(\d+\.\d+)/
    );

  if (m) {
    return {
      lat: parseFloat(m[1]),
      lng: parseFloat(m[2])
    };
  }

  m =
    location.match(
      /(\d+\.\d+)\s+(\d+\.\d+)/
    );

  if (m) {
    return {
      lat: parseFloat(m[1]),
      lng: parseFloat(m[2])
    };
  }

  return {
    lat: null,
    lng: null
  };
}

// ============================================================
// 🚀 Handler
// ============================================================

module.exports = async function handler(req, res) {

  res.setHeader(
    'Access-Control-Allow-Origin',
    '*'
  );

  res.setHeader(
    'Access-Control-Allow-Methods',
    'GET, POST, OPTIONS'
  );

  res.setHeader(
    'Access-Control-Allow-Headers',
    'Content-Type, Authorization'
  );


  if (req.method === 'OPTIONS') {
    return res.status(200).end();
  }


  if (req.method !== 'POST') {
    return res.status(405).json({
      error: 'Method not allowed'
    });
  }


  if (!SUPABASE_KEY) {
    return res.status(500).json({
      error: 'Supabase key missing'
    });
  }


  const supabase =
    createClient(
      SUPABASE_URL,
      SUPABASE_KEY
    );


  console.log(
    '⚡ Fast Sync 시작'
  );


  try {

    const body =
      req.body || {};

    const vehicleId =
      body.vehicleId ||
      body.vehicle_id ||
      VEHICLE_ID;


    // ========================================================
    // 🔑 Google 인증
    // ========================================================

    const token =
      await getGoogleIdToken();

    if (!token) {

      return res.status(401).json({
        success: false,
        error: 'Google auth failed'
      });
    }


    // ========================================================
    // 📊 마지막 기록 시간
    // ========================================================

    const last =
      await getLastRecordTimestamps(
        supabase
      );


    const since = {};


    for (
      const key of [
        'driving',
        'vehicle',
        'charging'
      ]
    ) {

      if (last[key]) {

        const d =
          new Date(last[key]);

        // 최근 24시간을 다시 조회
        // Firestore 정렬/시간 차이로 인한 누락 방지
        d.setDate(
          d.getDate() - 1
        );

        since[key] = d;

      } else {

        since[key] = null;
      }
    }


    // ========================================================
    // 🆔 기존 Supabase ID
    // ========================================================

    const existingIds = {};


    for (
      const table of [
        'vehicle',
        'driving',
        'charging'
      ]
    ) {

      const { data, error } =
        await supabase
          .from(table)
          .select('id');


      if (error) {

        console.warn(
          `⚠️ [${table}] 기존 ID 조회 실패:`,
          error.message
        );

        existingIds[table] =
          new Set();

      } else {

        existingIds[table] =
          new Set(
            (data || [])
              .map(row => row.id)
          );
      }
    }


    const collections = [

      {
        name: 'vehicle_state',
        table: 'vehicle',
        since: since.vehicle
      },

      {
        name: 'driving',
        table: 'driving',
        since: since.driving
      },

      {
        name: 'charging',
        table: 'charging',
        since: since.charging
      }

    ];


    const summary = {
      vehicle: 0,
      driving: 0,
      charging: 0,
      duration_fixed: 0
    };


    // ========================================================
    // 🔄 Collection 처리
    // ========================================================

    for (
      const col of collections
    ) {

      console.log(
        `\n📥 [${col.table}] Firestore 조회`
      );


      const docs =
        await withTimeout(
          fetchFirestoreDocsSince(
            col.name,
            token,
            col.since
          ),
          8000
        );


      console.log(
        `📦 [${col.table}] 수신: ${docs.length}건`
      );


      const newDocs =
        docs.filter(doc => {

          const docId =
            doc.name
              ?.split('/')
              .pop();

          return (
            docId &&
            !existingIds[col.table]
              .has(docId)
          );
        });


      console.log(
        `🆕 [${col.table}] 신규: ${newDocs.length}건`
      );


      if (!newDocs.length) {
        continue;
      }


      const batch = [];


      // ======================================================
      // 📄 각 Firestore 문서
      // ======================================================

      for (
        const doc of newDocs
      ) {

        const parsed =
          parseFirestoreFields(
            doc.fields || {}
          );


        const docId =
          doc.name
            ?.split('/')
            .pop();


        if (!docId) {
          continue;
        }


        const base = {
          id: docId,
          user_uid: USER_UID,
          vehicle_id: vehicleId
        };


        // ====================================================
        // 🚗 VEHICLE
        // ====================================================

        if (
          col.table === 'vehicle'
        ) {

          const logs =
            Array.isArray(
              parsed.stateLogs
            )
              ? parsed.stateLogs
              : [];


          const latest =
            logs.length
              ? logs[logs.length - 1]
              : parsed;


          const rawStart =
            latest.startDateTime ??
            latest.start_time ??
            parsed.startDateTime ??
            parsed.start_time ??
            null;


          const rawEnd =
            latest.endDateTime ??
            latest.end_time ??
            parsed.endDateTime ??
            parsed.end_time ??
            null;


          const startTime =
            safeToISOString(
              rawStart,
              null
            );


          const endTime =
            safeToISOString(
              rawEnd,
              null
            );


          let durationMinutes = 0;


          if (
            startTime &&
            endTime
          ) {

            durationMinutes =
              calculateDurationMinutes(
                startTime,
                endTime,
                0
              );
          }


          batch.push({

            ...base,

            state:
              String(
                latest.state ||
                parsed.state ||
                'online'
              ).toLowerCase(),

            battery_level:
              Number(
                latest.battery_level ??
                parsed.battery_level ??
                0
              ),

            battery_range:
              Number(
                latest.battery_range ??
                parsed.battery_range ??
                0
              ),

            added_charging:
              Number(
                latest.addedCharging ??
                parsed.addedCharging ??
                0
              ),

            outside_temp:
              Number(
                latest.out_temp ??
                latest.outside_temp ??
                parsed.out_temp ??
                0
              ),

            duration_min:
              durationMinutes,

            start_time:
              startTime,

            end_time:
              endTime,

            odometer:
              Math.round(
                Number(
                  latest.odometer ??
                  parsed.odometer ??
                  0
                ) * 10
              ) / 10,

            updated_at:
              new Date().toISOString(),

            raw_data:
              parsed
          });


          continue;
        }


        // ====================================================
        // 🛣️ DRIVING
        // ====================================================

        if (
          col.table === 'driving'
        ) {

          const dist =
            Number(
              parsed.moveKM ??
              parsed.move_km ??
              parsed.distance_km ??
              parsed.distance ??
              0
            );


          // 50m 미만 쓰레기 데이터 차단
          if (
            !isFinite(dist) ||
            dist < 0.05
          ) {

            console.log(
              `⏭️ [driving] ` +
              `${docId} 거리 ${dist}km → 스킵`
            );

            continue;
          }


          // --------------------------------------------------
          // 실제 start / end 시간 찾기
          // --------------------------------------------------

          const rawStart =
            parsed.start_time ??
            parsed.startTime ??
            parsed.startDateTime ??
            parsed.started_at ??
            null;


          const rawEnd =
            parsed.end_time ??
            parsed.endTime ??
            parsed.endDateTime ??
            parsed.ended_at ??
            null;


          const createdFallback =
            safeToISOString(
              parsed.date ??
              parsed.created_at ??
              doc.createTime,
              new Date().toISOString()
            );


          const startTime =
            safeToISOString(
              rawStart,
              createdFallback
            );


          const endTime =
            safeToISOString(
              rawEnd,
              startTime
            );


          // --------------------------------------------------
          // Firestore duration
          // --------------------------------------------------

          const firestoreDuration =
            parseDrivingTimeToMinutes(
              parsed.duration_min ??
              parsed.driving_time ??
              parsed.duration ??
              parsed.durationMin ??
              0
            );


          // --------------------------------------------------
          // 실제 시간 차이를 최우선 사용
          // --------------------------------------------------

          let durationMinutes =
            calculateDurationMinutes(
              startTime,
              endTime,
              firestoreDuration
            );


          // 잘못된 음수/NaN 방지
          if (
            !isFinite(durationMinutes) ||
            durationMinutes < 0
          ) {
            durationMinutes = 0;
          }


          const locList =
            parsed.location_list ??
            parsed.path ??
            [];


          const useBattery =
            Number(
              parsed.useBattery ??
              parsed.use_battery ??
              parsed.battery_used ??
              0
            );


          const outsideTempRaw =
            parsed.outside_temp ??
            parsed.outTemp ??
            null;


          const outsideTemp =
            outsideTempRaw === null
              ? null
              : Number(
                  outsideTempRaw
                );


          const startDong =
            parsed.start_dong ??
            parsed.startDong ??
            parsed.start_address ??
            parsed.startAddress ??
            null;


          const endDong =
            parsed.end_dong ??
            parsed.endDong ??
            parsed.end_address ??
            parsed.endAddress ??
            null;


          const newRecord = {

            ...base,

            distance_km:
              dist,

            move_km:
              dist,

            use_battery:
              isFinite(useBattery)
                ? useBattery
                : 0,

            duration_min:
              durationMinutes,

            driving_time:
              durationMinutes,

            start_time:
              startTime,

            end_time:
              endTime,

            start_dong:
              startDong,

            end_dong:
              endDong,

            start_address:
              startDong,

            end_address:
              endDong,

            location_list:
              Array.isArray(locList)
                ? locList
                : [],

            outside_temp:
              isFinite(outsideTemp)
                ? outsideTemp
                : null,

            created_at:
              createdFallback
          };


          // ==================================================
          // 🔥 신규 batch 내부 중복 방지
          //
          // 같은 시간 ±5분
          // + 거리 차이 < 0.5km
          //
          // 단순 created_at이 아니라
          // 실제 start_time을 기준으로 비교
          // ==================================================

          const DEDUP_WINDOW_MS =
            5 * 60 * 1000;


          const newStartMs =
            newRecord.start_time
              ? new Date(
                  newRecord.start_time
                ).getTime()
              : null;


          let dupIdx = -1;


          if (
            newStartMs &&
            isFinite(newStartMs)
          ) {

            dupIdx =
              batch.findIndex(
                item => {

                  if (
                    !item.start_time
                  ) {
                    return false;
                  }


                  const itemStartMs =
                    new Date(
                      item.start_time
                    ).getTime();


                  if (
                    !isFinite(
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


                  const distDiff =
                    Math.abs(
                      Number(
                        item.distance_km ||
                        item.move_km ||
                        0
                      ) -
                      Number(
                        newRecord.distance_km ||
                        0
                      )
                    );


                  return (
                    timeDiff <=
                      DEDUP_WINDOW_MS &&
                    distDiff < 0.5
                  );
                }
              );
          }


          if (
            dupIdx !== -1
          ) {

            console.log(
              `⏭️ [driving] ` +
              `유사 운행 중복 스킵: ` +
              `${docId}`
            );


            // 시간이 더 긴 기록을 보존
            if (
              (
                newRecord.duration_min ||
                0
              ) >
              (
                batch[dupIdx]
                  .duration_min ||
                0
              )
            ) {

              batch[dupIdx] =
                newRecord;

              console.log(
                `🔄 [driving] ` +
                `더 정확한 긴 기록으로 교체`
              );
            }


            continue;
          }


          batch.push(
            newRecord
          );

          continue;
        }


        // ====================================================
        // 🔌 CHARGING
        // ====================================================

        if (
          col.table === 'charging'
        ) {

          const nowBattery =
            Number(
              parsed.nowBattery ??
              parsed.now_battery ??
              parsed.battery_level ??
              0
            );


          const addedBatteryLevel =
            Number(
              parsed.addedBatteryLevel ??
              parsed.added_battery_level ??
              parsed.added_battery ??
              0
            );


          const startBattery =
            Number(
              parsed.startBattery ??
              parsed.start_battery ??
              0
            );


          const addedCharging =
            Number(
              parsed.addedCharging ??
              parsed.added_charging ??
              parsed.added_kwh ??
              0
            );


          const addedBatteryRange =
            Number(
              parsed.addedBatteryRange ??
              parsed.added_battery_range ??
              parsed.added_range ??
              0
            );


          let finalStartBattery =
            startBattery;


          if (
            finalStartBattery === 0 &&
            nowBattery > 0 &&
            addedBatteryLevel > 0
          ) {

            finalStartBattery =
              Math.max(
                0,
                nowBattery -
                addedBatteryLevel
              );
          }


          let locList =
            parsed.location_list ??
            parsed.location ??
            parsed.path ??
            [];


          if (
            typeof locList === 'string'
          ) {

            try {

              locList =
                JSON.parse(locList);

            } catch (e) {

              locList = [];
            }
          }


          if (
            !Array.isArray(locList)
          ) {

            locList = [];
          }


          const rawDate =
            parsed.date ??
            parsed.created_at ??
            parsed.timestamp ??
            doc.createTime ??
            null;


          const dateISO =
            safeToISOString(
              rawDate,
              new Date().toISOString()
            );


          const newRecord = {

            ...base,

            date:
              dateISO,

            now_battery:
              Math.round(
                nowBattery * 100
              ) / 100,

            added_battery_level:
              Math.round(
                addedBatteryLevel * 100
              ) / 100,

            start_battery:
              Math.round(
                finalStartBattery * 100
              ) / 100,

            added_charging:
              Math.round(
                addedCharging * 100
              ) / 100,

            added_battery_range:
              Math.round(
                addedBatteryRange * 100
              ) / 100,

            location_list:
              locList,

            raw_data:
              parsed,

            created_at:
              dateISO
          };


          // --------------------------------------------------
          // 충전 중복
          // --------------------------------------------------

          const DEDUP_WINDOW_MS =
            5 * 60 * 1000;


          const newDateMs =
            newRecord.date
              ? new Date(
                  newRecord.date
                ).getTime()
              : null;


          let dupIdx = -1;


          if (
            newDateMs &&
            isFinite(newDateMs)
          ) {

            dupIdx =
              batch.findIndex(
                item => {

                  if (!item.date) {
                    return false;
                  }


                  const itemDateMs =
                    new Date(
                      item.date
                    ).getTime();


                  if (
                    !isFinite(
                      itemDateMs
                    )
                  ) {
                    return false;
                  }


                  const diff =
                    Math.abs(
                      itemDateMs -
                      newDateMs
                    );


                  const batDiff =
                    Math.abs(
                      Number(
                        item.added_battery_level ||
                        0
                      ) -
                      Number(
                        newRecord.added_battery_level ||
                        0
                      )
                    );


                  return (
                    diff <=
                      DEDUP_WINDOW_MS &&
                    batDiff < 5
                  );
                }
              );
          }


          if (
            dupIdx !== -1
          ) {

            if (
              (
                newRecord.added_charging ||
                0
              ) >
              (
                batch[dupIdx]
                  .added_charging ||
                0
              )
            ) {

              batch[dupIdx] =
                newRecord;
            }

            continue;
          }


          batch.push(
            newRecord
          );
        }
      }


      // ======================================================
      // 💾 저장
      // ======================================================

      if (
        batch.length
      ) {

        const saved =
          await upsertWithRetry(
            supabase,
            col.table,
            batch
          );


        summary[col.table] =
          saved;


        console.log(
          `✅ [${col.table}] ` +
          `${saved}건 저장`
        );
      }
    }


    // ========================================================
    // 🔧 driving 시간 최종 보정
    //
    // Firestore에서 start/end를 못 가져온 기록이나
    // 기존 기록 중 duration이 0/NULL인 경우
    // Supabase의 실제 timestamp를 기준으로 보정
    // ========================================================

    console.log(
      '🔧 [driving] duration 최종 보정 시작'
    );


    try {

      const {
        data: fixedCount,
        error: durationError
      } =
        await supabase.rpc(
          'fix_driving_duration'
        );


      if (durationError) {

        console.error(
          '❌ [driving] duration RPC 실패:',
          durationError.message
        );

      } else {

        summary.duration_fixed =
          Number(
            fixedCount || 0
          );


        console.log(
          `🔧 [driving] duration 보정 완료: ` +
          `${summary.duration_fixed}건`
        );
      }

    } catch (e) {

      console.error(
        '❌ [driving] duration RPC 실행 오류:',
        e.message
      );
    }


    // ========================================================
    // 📍 outside_temp 보정
    // ========================================================

    try {

      const {
        error
      } =
        await supabase.rpc(
          'update_driving_outside_temp'
        );


      if (error) {

        console.warn(
          '⚠️ outside_temp RPC 실패:',
          error.message
        );
      }

    } catch (e) {

      console.warn(
        '⚠️ outside_temp RPC 오류:',
        e.message
      );
    }


    // ========================================================
    // 📅 Calendar
    //
    // Fast Sync에서는 처리하지 않음.
    // Full sync.js에서 처리.
    // ========================================================

    console.log(
      '⏭️ 캘린더 동기화는 ' +
      'Full Sync(sync.js)에서 처리됩니다.'
    );


    // ========================================================
    // ✅ 결과
    // ========================================================

    return res.status(200).json({

      success: true,

      message:
        'Fast sync done',

      summary

    });


  } catch (err) {

    console.error(
      '❌ Fast Sync Error:',
      err.message
    );


    return res.status(500).json({

      success: false,

      error:
        err.message

    });
  }
};
