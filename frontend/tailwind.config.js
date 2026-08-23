module.exports = {
  content: ['../src/main/resources/templates/**/*.html', '../src/main/resources/static/js/time-picker.js'],
  theme: { extend: {
    colors: {
      brand: {50:'#f6f4fb',100:'#ece8f7',200:'#dcd4f0',300:'#c3b5e4',400:'#a999d9',500:'#8f7ac9',600:'#7b64b6',700:'#69529e',800:'#574382',900:'#483769',950:'#2d2344'},
      ink: {DEFAULT:'#23222f',50:'#f7f7fa',100:'#ececf1',200:'#d8d7e0',300:'#b8b7c6',400:'#908fa3',500:'#6b6a80',600:'#4f4e63',700:'#3d3c4f',800:'#2e2d3d',900:'#23222f'}
    },
    fontFamily: { display:['"Plus Jakarta Sans"','sans-serif'], body:['Inter','sans-serif'] },
    boxShadow: {
      card: '0 1px 2px 0 rgba(35,34,47,.05), 0 6px 20px -8px rgba(35,34,47,.10)',
      cardHover: '0 2px 4px 0 rgba(35,34,47,.06), 0 14px 32px -10px rgba(35,34,47,.16)',
      pop: '0 8px 30px -6px rgba(72,55,105,.28)'
    }
  }}
}
