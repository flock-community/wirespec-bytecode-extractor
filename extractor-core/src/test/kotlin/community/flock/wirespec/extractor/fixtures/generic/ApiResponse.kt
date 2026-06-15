package community.flock.wirespec.extractor.fixtures.generic

data class ApiResponse<T>(val data: T, val status: Int)
