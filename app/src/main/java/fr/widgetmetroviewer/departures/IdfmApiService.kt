package fr.widgetmetroviewer.departures

internal enum class IdfmApiService(val label: String, val fileName: String) {
    UNIT("Passages · unitaire", "idfm_api_usage.json"),
    LINE("Passages · par ligne", "idfm_api_usage_line.json"),
    TRAFFIC_REPORTS("État de circulation et correspondances · Info Trafic v2", "idfm_api_usage_traffic_reports.json"),
    TRAFFIC("Messages des écrans · trafic", "idfm_api_usage_traffic.json")
}
