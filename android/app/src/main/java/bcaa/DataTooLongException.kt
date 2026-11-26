package bcaa

open class DataTooLongException @JvmOverloads constructor(message: String? = null) :
    IllegalArgumentException(message)
