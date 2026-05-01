package app.aaps.auto

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import dagger.hilt.android.EntryPointAccessors

class AapsCarAppService : CarAppService() {

    override fun onCreateSession(): Session {
        val deps = EntryPointAccessors.fromApplication(
            applicationContext,
            AutoDependencies::class.java
        )
        return AapsCarSession(deps)
    }

    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
}
