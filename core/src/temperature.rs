use serde_json::{Value, json};

/// The reviewed IPQ5018 vendor driver returns degrees, unlike generic Linux.
/// Keep the source value and unit in the snapshot; never guess from magnitude.
pub fn normalize(snapshot: &mut Value) {
    let Some(sensors) = snapshot["temperatures"].as_array_mut() else {
        snapshot["temperatures"] = json!([]);
        return;
    };
    for sensor in sensors {
        let celsius = match (sensor["raw"].as_f64(), sensor["unit"].as_str()) {
            (Some(raw), Some("celsius")) => Some(raw),
            (Some(raw), Some("millicelsius")) => Some(raw / 1000.0),
            _ => None,
        }
        .filter(|n| n.is_finite() && (-50.0..=200.0).contains(n));
        sensor["celsius"] = json!(celsius);
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn oem_whole_degrees_and_generic_millidegrees_are_distinct() {
        let mut v = json!({"temperatures":[{"raw":74,"unit":"celsius"},{"raw":52500,"unit":"millicelsius"},{"raw":74,"unit":"millicelsius"},{"raw":55,"unit":"unknown"},{"raw":-273000,"unit":"millicelsius"}]});
        normalize(&mut v);
        assert_eq!(v["temperatures"][0]["celsius"], 74.0);
        assert_eq!(v["temperatures"][1]["celsius"], 52.5);
        assert_eq!(v["temperatures"][2]["celsius"], 0.074);
        assert!(v["temperatures"][3]["celsius"].is_null());
        assert!(v["temperatures"][4]["celsius"].is_null());
    }
    #[test]
    fn legacy_preconverted_data_is_not_reused_as_a_sensor_measurement() {
        let mut v = json!({"temperatures":[{"celsius":0.1}]});
        normalize(&mut v);
        assert!(v["temperatures"][0]["celsius"].is_null());
    }
}
