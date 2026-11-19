import SwiftUI

/// Parents' PIN: set a new one or check it.
struct PinSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let request: PinRequest
    @State private var pin = ""
    @State private var repeatPin = ""
    @State private var wrong = false
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SecureField("PIN (4 Ziffern)", text: $pin).keyboardType(.numberPad).focused($focused)
                    if request.setNew { SecureField("PIN wiederholen", text: $repeatPin).keyboardType(.numberPad) }
                } footer: {
                    if wrong { Text(request.setNew ? "Die beiden PINs stimmen nicht überein." : "Falsche PIN.").foregroundStyle(Theme.error) }
                    else if request.setNew { Text("Die PIN gilt nur auf diesem iPhone. Am Fernseher gibt es eine eigene.") }
                }
            }
            .navigationTitle(request.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("OK", action: confirm).disabled(pin.count < 4)
                }
            }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium])
    }

    private func confirm() {
        if request.setNew {
            guard pin == repeatPin else { wrong = true; return }
            ParentPin.set(pin)
        } else if !ParentPin.check(pin) {
            wrong = true
            pin = ""
            return
        }
        dismiss()
        request.onSuccess()
    }
}

/// Screen time is up (or bedtime): parents can add 30 minutes with the PIN.
struct LockedView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let reason: String

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "moon.stars").font(.system(size: 56, weight: .light)).foregroundStyle(Theme.muted)
            Text(reason).font(Theme.title(28)).multilineTextAlignment(.center).foregroundStyle(Theme.text)
            Text("Morgen geht's weiter.").foregroundStyle(Theme.muted)
            Button("Eltern: 30 Minuten mehr") {
                let p = model.profile
                dismiss()
                model.pinRequest = PinRequest(title: "Eltern-PIN für 30 Minuten mehr", setNew: !ParentPin.isSet) {
                    model.screenTime.addBonus(30, for: p)
                }
            }
            .buttonStyle(QuietButton()).padding(.top, 20)
            Button("OK") { dismiss() }.buttonStyle(AccentButton())
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.background.ignoresSafeArea())
    }
}
