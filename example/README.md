# easybis_usb_serial example

A bench app: lists the USB devices (vendor:product id), opens one with a chosen driver and port
settings (DTR/RTS switch included) and shows the received bytes as text and hex.

Create the platform folders once, then run it on an Android phone with a USB-serial device
attached (USB OTG):

    flutter create --platforms=android .
    flutter run
