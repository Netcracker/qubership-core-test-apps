package workload

// Outcome is one operation the workload performed, as the application saw it.
type Outcome struct {
	Sequence        int64  `json:"sequence"`
	StartedAtMillis int64  `json:"startedAtMillis"`
	DurationMillis  int64  `json:"durationMillis"`
	Success         bool   `json:"success"`
	ErrorClass      string `json:"errorClass,omitempty"`
	ErrorMessage    string `json:"errorMessage,omitempty"`
}

// Stats is the timeline the suite asserts on, plus counters that make a failure readable.
// The field names are the contract shared with the other test applications.
type Stats struct {
	Running                            bool      `json:"running"`
	Storage                            string    `json:"storage"`
	HandleMode                         string    `json:"handleMode"`
	StartedAtMillis                    int64     `json:"startedAtMillis"`
	Total                              int64     `json:"total"`
	Succeeded                          int64     `json:"succeeded"`
	Failed                             int64     `json:"failed"`
	FirstFailureAtMillis               *int64    `json:"firstFailureAtMillis"`
	LastFailureAtMillis                *int64    `json:"lastFailureAtMillis"`
	FirstSuccessAfterLastFailureMillis *int64    `json:"firstSuccessAfterLastFailureMillis"`
	MaxDurationMillis                  int64     `json:"maxDurationMillis"`
	Outcomes                           []Outcome `json:"outcomes"`
}
