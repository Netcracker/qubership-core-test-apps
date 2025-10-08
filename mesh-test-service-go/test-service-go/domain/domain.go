package domain

type TraceResponse struct {
	ServiceName string `json:"serviceName"`
	FamilyName  string `json:"familyName"`
	Namespace   string `json:"namespace"`
	Version     string `json:"version"`
	PodId       string `json:"podId"`

	RequestHost  string `json:"requestHost"`
	ServerHost   string `json:"serverHost"`
	RemoteAddr   string `json:"remoteAddr"`
	Path         string `json:"path"`
	Method       string `json:"method"`
	Xversion     string `json:"xversion"`
	XVersionName string `json:"xVersionName"`
}
