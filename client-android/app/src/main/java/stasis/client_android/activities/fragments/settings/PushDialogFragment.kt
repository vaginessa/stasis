package stasis.client_android.activities.fragments.settings

import android.graphics.Typeface
import android.os.Bundle
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputLayout
import stasis.client_android.R
import stasis.client_android.activities.helpers.Common
import stasis.client_android.activities.helpers.Common.renderAsSpannable
import stasis.client_android.lib.security.exceptions.InvalidUserCredentials
import stasis.client_android.lib.utils.Try

class PushDialogFragment(
    private val server: String,
    private val pushSecret: (String, String?, f: (Try<Unit>) -> Unit) -> Unit
) : DialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val view = inflater.inflate(R.layout.dialog_device_secret_push, container, false)

        val passwordView =
            view.findViewById<TextInputLayout>(R.id.push_device_secret_password)

        val passwordConfirmationView =
            view.findViewById<TextInputLayout>(R.id.push_device_secret_password_confirmation)

        val remotePasswordView =
            view.findViewById<TextInputLayout>(R.id.push_device_secret_remote_password)

        val remotePasswordShowButton =
            view.findViewById<TextView>(R.id.push_device_secret_show_remote_password)

        passwordView.setStartIconOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_manage_device_secret_push_password_hint)
                .setMessage(getString(R.string.settings_manage_device_secret_push_password_hint_extra))
                .show()
        }

        passwordConfirmationView.setStartIconOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_manage_device_secret_push_password_confirmation_hint)
                .setMessage(getString(R.string.settings_manage_device_secret_push_password_confirmation_hint_extra))
                .show()
        }

        remotePasswordView.setStartIconOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_manage_device_secret_push_remote_password_hint)
                .setMessage(getString(R.string.settings_manage_device_secret_push_remote_password_hint_extra))
                .show()
        }

        remotePasswordShowButton.setOnClickListener {
            remotePasswordView.isVisible = true
            remotePasswordShowButton.isVisible = false
        }

        view.findViewById<TextView>(R.id.push_device_secret_info).text =
            getString(R.string.settings_manage_device_secret_push_confirm_text)
                .renderAsSpannable(
                    Common.StyledString(
                        placeholder = "%1\$s",
                        content = server,
                        style = StyleSpan(Typeface.BOLD)
                    ),
                    Common.StyledString(
                        placeholder = "%2\$s",
                        content = getString(R.string.settings_manage_device_secret_push_confirm_text_note),
                        style = StyleSpan(Typeface.ITALIC)
                    )
                )

        val inProgress = view.findViewById<CircularProgressIndicator>(R.id.push_device_secret_in_progress)
        val confirmButton = view.findViewById<Button>(R.id.push_device_secret_confirm)

        view.findViewById<Button>(R.id.push_device_secret_cancel).setOnClickListener {
            dialog?.dismiss()
        }

        confirmButton.setOnClickListener {
            passwordView.isErrorEnabled = false
            passwordView.error = null
            passwordConfirmationView.isErrorEnabled = false
            passwordConfirmationView.error = null

            val password = passwordView.editText?.text?.toString().orEmpty()
            val passwordConfirmation = passwordConfirmationView.editText?.text?.toString().orEmpty()
            val remotePassword = remotePasswordView.editText?.text?.toString()?.ifEmpty { null }

            when {
                password == passwordConfirmation && password.isNotEmpty() -> {
                    confirmButton.isVisible = false
                    inProgress.isVisible = true

                    pushSecret(password, remotePassword) {
                        if (it is Try.Failure && it.exception is InvalidUserCredentials) {
                            passwordView.isErrorEnabled = true
                            passwordView.error =
                                getString(R.string.settings_manage_device_secret_push_invalid_current_password)
                            confirmButton.isVisible = true
                            inProgress.isVisible = false
                        } else {
                            dialog?.dismiss()
                        }
                    }
                }

                password.isEmpty() -> {
                    passwordView.isErrorEnabled = true
                    passwordView.error = getString(R.string.settings_manage_device_secret_push_empty_password)
                }

                else -> {
                    passwordView.isErrorEnabled = true
                    passwordView.error =
                        getString(R.string.settings_manage_device_secret_push_mismatched_passwords)
                    passwordConfirmationView.isErrorEnabled = true
                    passwordConfirmationView.error =
                        getString(R.string.settings_manage_device_secret_push_mismatched_passwords)
                }
            }


        }

        return view
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    companion object {
        const val DialogTag: String =
            "stasis.client_android.activities.fragments.settings.PushDialogFragment"
    }
}